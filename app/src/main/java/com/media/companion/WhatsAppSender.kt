package com.media.companion

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sends the auto-reply over WhatsApp from the phone's OWN WhatsApp number.
 *
 * Flow (fully automatic once Accessibility is on):
 *  1. Each reply is put in a small persistent queue.
 *  2. When the phone is awake + unlocked, the chat is opened with the message
 *     pre-filled and WhatsAppAutoSendService taps Send, then goes Home.
 *  3. If the phone is locked when the call ends, the reply waits in the queue
 *     and goes out by itself the moment the phone is unlocked — no tap.
 *  4. Only if Accessibility is OFF (or a reply keeps failing / waits > 6 h)
 *     does it fall back to the one-tap notification, and it then also shows
 *     a "turn auto-send back on" alert so the owner knows why.
 *
 * Automating WhatsApp is against WhatsApp's terms and can get the number
 * restricted — scoped to transactional call replies only, not bulk sending.
 */
object WhatsAppSender {

    private const val CC = "91"
    private const val PREFS = "media_wa_queue"
    private const val MAX_AGE_MS = 6 * 3600_000L
    private const val SEND_TIMEOUT_MS = 20_000L
    private const val MAX_ATTEMPTS = 3

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var pendingNumber: String? = null
    @Volatile private var pendingUntil: Long = 0L
    @Volatile private var pendingOpened = false

    // ── Diagnostics: the last thing that happened, shown in the STATUS card,
    // so a failure says exactly which step broke instead of "not sent".
    fun lastResult(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", "") ?: ""

    private fun setResult(c: Context, text: String) {
        val t = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", "$t — $text").apply()
    }

    /** Called by the Accessibility service as soon as a WhatsApp window appears. */
    fun onWhatsAppOpened(context: Context) {
        if (!hasPending() || pendingOpened) return
        pendingOpened = true
        setResult(context, "WhatsApp opened for $pendingNumber, tapping Send…")
    }

    fun hasPending(): Boolean = pendingNumber != null && System.currentTimeMillis() < pendingUntil
    fun clearPending() { pendingNumber = null; pendingUntil = 0L }

    /** Called by the Accessibility service after it tapped Send. */
    fun onSent(context: Context) {
        val n = pendingNumber
        clearPending()
        setResult(context, "Sent to $n ✓")
        if (n != null) {
            val entry = findInQueue(context, n)
            removeFromQueue(context, n)
            // Log to the Media dashboard only now that it really went out.
            if (entry != null) {
                val app = context.applicationContext
                Thread {
                    SmsEngine.reportWhatsappSent(app, n, entry.optString("m"),
                        entry.optString("e"), entry.optLong("s", System.currentTimeMillis()))
                }.start()
            }
        }
        // Next queued reply (if any) after WhatsApp has settled.
        main.postDelayed({ process(context) }, 2500)
    }

    /** Called when WhatsApp says the number isn't on WhatsApp — drop it. */
    fun onInvalidNumber(context: Context) {
        val n = pendingNumber
        clearPending()
        setResult(context, "$n is not on WhatsApp — skipped")
        if (n != null) removeFromQueue(context, n)
        main.postDelayed({ process(context) }, 1500)
    }

    fun send(
        context: Context, number: String, message: String, useBusiness: Boolean,
        eventId: String = "", startTime: Long = System.currentTimeMillis()
    ) {
        val pkg = if (useBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        if (!isAccessibilityEnabled(context)) {
            notifyOneTap(context, number, message, pkg)
            notifyAutoSendOff(context)
            setResult(context, "Auto-send (Accessibility) was OFF — sent as tap notification")
            return
        }
        enqueue(context, number, message, pkg, eventId, startTime)
        main.post { process(context) }
    }

    /**
     * Sends the next queued reply if the phone is ready. Safe to call any
     * time: on new replies, on unlock (USER_PRESENT), and when the
     * Accessibility service (re)connects.
     */
    fun process(context: Context) {
        val app = context.applicationContext
        val q = loadQueue(app)
        if (q.length() == 0) return

        if (!isAccessibilityEnabled(app)) {
            // Auto-send was switched off (some phones do this when they clear
            // background apps). Hand every waiting reply over as one-tap.
            for (i in 0 until q.length()) {
                val o = q.getJSONObject(i)
                notifyOneTap(app, o.getString("n"), o.getString("m"), o.getString("p"))
            }
            saveQueue(app, JSONArray())
            notifyAutoSendOff(app)
            setResult(app, "Auto-send (Accessibility) was OFF — sent as tap notification")
            return
        }
        if (hasPending()) return
        if (!isUnlocked(app)) { // will run again on unlock
            setResult(app, "Phone locked — WhatsApp reply waiting, sends on unlock")
            return
        }

        val now = System.currentTimeMillis()
        val o = q.getJSONObject(0)
        val number = o.getString("n"); val msg = o.getString("m"); val pkg = o.getString("p")
        val attempts = o.optInt("a", 0)

        if (now - o.optLong("t", now) > MAX_AGE_MS || attempts >= MAX_ATTEMPTS) {
            removeFromQueue(app, number)
            notifyOneTap(app, number, msg, pkg)
            main.post { process(app) }
            return
        }
        if (WhatsAppAutoSendService.instance == null) {
            // Enabled in Settings but the system hasn't (re)started it.
            setResult(app, "Auto-send is ON in Settings but not running — turn it OFF and ON again")
        }

        o.put("a", attempts + 1)
        saveQueue(app, q)
        pendingNumber = number
        pendingUntil = now + SEND_TIMEOUT_MS
        pendingOpened = false
        setResult(app, "Opening WhatsApp for $number (try ${attempts + 1}/$MAX_ATTEMPTS)…")
        if (!launchChat(app, number, msg, pkg)) {
            clearPending()
            removeFromQueue(app, number)
            setResult(app, if (pkg == "com.whatsapp.w4b") "WhatsApp Business is not installed — turn off 'Use the WhatsApp Business app'"
                           else "WhatsApp is not installed — or turn on 'Use the WhatsApp Business app'")
            return
        }
        // If Send wasn't tapped in time, record WHY, then retry (up to MAX_ATTEMPTS).
        main.postDelayed({
            if (pendingNumber == number && System.currentTimeMillis() >= pendingUntil) {
                setResult(app, if (!pendingOpened)
                    "BLOCKED: phone did not let WhatsApp open. Allow 'Display over other apps' and, on Realme/Oppo, App info → Other permissions → 'Display pop-up windows while running in background'"
                else
                    "WhatsApp opened but the Send button wasn't found — update WhatsApp, then try again")
                clearPending()
                process(app)
            }
        }, SEND_TIMEOUT_MS + 500)
    }

    /**
     * Opens the WhatsApp chat with the text pre-filled. Launched from the
     * Accessibility service when it's connected: Android lets a bound
     * Accessibility service start activities from the background, which a
     * plain background service often isn't allowed to (Android 10+).
     */
    private fun launchChat(context: Context, number: String, message: String, pkg: String): Boolean {
        val url = "https://api.whatsapp.com/send?phone=$CC$number&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launcher: Context = WhatsAppAutoSendService.instance ?: context
        return try {
            launcher.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isUnlocked(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val km = context.getSystemService(KeyguardManager::class.java)
        val awake = pm?.isInteractive ?: true
        val locked = km?.isKeyguardLocked ?: false
        return awake && !locked
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val pkg = context.packageName
        val am = context.getSystemService(AccessibilityManager::class.java)
        val viaManager = runCatching {
            am?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.any { it.resolveInfo?.serviceInfo?.let { s -> s.packageName == pkg && s.name.endsWith("WhatsAppAutoSendService") } == true }
        }.getOrNull() == true
        if (viaManager) return true
        val flat = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.contains("$pkg/$pkg.WhatsAppAutoSendService") ||
            flat.contains("$pkg/.WhatsAppAutoSendService")
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ── Persistent queue ──

    private fun loadQueue(c: Context): JSONArray =
        runCatching { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("q", "[]")) }
            .getOrDefault(JSONArray())

    private fun saveQueue(c: Context, q: JSONArray) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("q", q.toString()).apply()
    }

    private fun enqueue(c: Context, number: String, message: String, pkg: String, eventId: String, startTime: Long) {
        val q = loadQueue(c)
        val out = JSONArray()
        // One waiting reply per number — the newest message wins.
        for (i in 0 until q.length()) if (q.getJSONObject(i).optString("n") != number) out.put(q.getJSONObject(i))
        out.put(JSONObject().put("n", number).put("m", message).put("p", pkg)
            .put("t", System.currentTimeMillis()).put("a", 0)
            .put("e", eventId).put("s", startTime))
        saveQueue(c, out)
    }

    private fun removeFromQueue(c: Context, number: String) {
        val q = loadQueue(c)
        val out = JSONArray()
        for (i in 0 until q.length()) if (q.getJSONObject(i).optString("n") != number) out.put(q.getJSONObject(i))
        saveQueue(c, out)
    }

    fun queuedCount(c: Context): Int = loadQueue(c).length()

    /** Test path used by the app's test buttons — same queue and auto-send as a real call. */
    fun sendTest(context: Context, number: String, message: String, useBusiness: Boolean) {
        clearPending()
        send(context.applicationContext, number, message, useBusiness)
    }

    private fun findInQueue(c: Context, number: String): JSONObject? {
        val q = loadQueue(c)
        for (i in 0 until q.length()) if (q.getJSONObject(i).optString("n") == number) return q.getJSONObject(i)
        return null
    }

    // ── Notifications ──

    private fun channel(nm: NotificationManager): String {
        val id = "media_whatsapp"
        nm.createNotificationChannel(NotificationChannel(id, "WhatsApp replies", NotificationManager.IMPORTANCE_HIGH))
        return id
    }

    /** Fallback: a notification that opens WhatsApp with the reply pre-filled. */
    private fun notifyOneTap(context: Context, number: String, message: String, pkg: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val url = "https://api.whatsapp.com/send?phone=$CC$number&text=${Uri.encode(message)}"
        val tap = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(pkg) }
        val pi = PendingIntent.getActivity(context, number.hashCode(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(context, channel(nm))
            .setContentTitle("Send WhatsApp to $number")
            .setContentText("Tap to open WhatsApp with your reply ready to send.")
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(("wa_$number").hashCode(), notif)
    }

    /** Tells the owner WHY it wasn't automatic, with a one-tap way to fix it. */
    private fun notifyAutoSendOff(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 7071, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(context, channel(nm))
            .setContentTitle("WhatsApp auto-send is OFF")
            .setContentText("The phone switched it off. Tap → \"Turn on automatic sending\".")
            .setStyle(Notification.BigTextStyle().bigText(
                "The phone switched off WhatsApp auto-send (Accessibility), so replies need a tap. " +
                "Tap here → \"Turn on automatic sending\", and also tap \"Stop battery from killing the app\" so it stays on."))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(7071, notif)
    }
}
