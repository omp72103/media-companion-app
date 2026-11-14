package com.media.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Sends the auto-reply over WhatsApp from the phone's OWN WhatsApp number.
 *
 * There is no official API for this, so it works the way tools like CONNECTit
 * do: it opens the WhatsApp chat with the message pre-filled, and an
 * Accessibility service (WhatsAppAutoSendService) taps "send". If that service
 * isn't enabled (e.g. a Pixel blocks it), it degrades to a one-tap
 * notification the owner taps to send — never silently doing nothing.
 *
 * Trade-offs are real and were accepted deliberately: automating WhatsApp is
 * against WhatsApp's terms and can get the number banned; the Accessibility
 * permission also raises Play Protect's suspicion. Scoped to transactional
 * missed-call replies only — this is not a bulk sender.
 */
object WhatsAppSender {

    // Country code prefix used for wa.me links (India, matching the backend).
    private const val CC = "91"

    @Volatile private var pendingNumber: String? = null
    @Volatile private var pendingUntil: Long = 0L

    fun hasPending(): Boolean = pendingNumber != null && System.currentTimeMillis() < pendingUntil
    fun clearPending() { pendingNumber = null; pendingUntil = 0L }
    fun onSent() { clearPending() }

    fun send(context: Context, number: String, message: String, useBusiness: Boolean) {
        val pkg = if (useBusiness) "com.whatsapp.w4b" else "com.whatsapp"
        if (isAccessibilityEnabled(context)) {
            pendingNumber = number
            pendingUntil = System.currentTimeMillis() + 25_000L
            if (!launchChat(context, number, message, pkg)) clearPending()
        } else {
            notifyOneTap(context, number, message, pkg)
        }
    }

    /** Opens the WhatsApp chat with the text pre-filled. Returns false if the app isn't installed. */
    private fun launchChat(context: Context, number: String, message: String, pkg: String): Boolean {
        val url = "https://api.whatsapp.com/send?phone=$CC$number&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val pkg = context.packageName
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

    /** Fallback when auto-send isn't available: a notification that opens WhatsApp pre-filled. */
    private fun notifyOneTap(context: Context, number: String, message: String, pkg: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "media_whatsapp"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "WhatsApp replies", NotificationManager.IMPORTANCE_HIGH)
        )
        val url = "https://api.whatsapp.com/send?phone=$CC$number&text=${Uri.encode(message)}"
        val tap = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(pkg) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, number.hashCode(), tap, flags)
        val notif = Notification.Builder(context, channelId)
            .setContentTitle("Send WhatsApp to $number")
            .setContentText("Tap to open WhatsApp with your reply ready to send.")
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(("wa_$number").hashCode(), notif)
    }
}
