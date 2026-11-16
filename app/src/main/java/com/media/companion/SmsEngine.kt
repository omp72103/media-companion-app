package com.media.companion

import android.content.Context
import android.os.Build
import android.provider.CallLog
import android.telephony.SmsManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The decision + send + report engine — runs entirely on the phone.
 *
 * For each detected call it: evaluates the cached rules (triggers, recipient
 * condition, business hours, cooldown), renders the template, sends the SMS
 * itself via SmsManager using the phone's OWN SIM (zero cost per send — uses
 * the phone's existing SMS pack), then reports the outcome to nativeCallLog
 * so the dashboard's Call/SMS history reflects it.
 */
object SmsEngine {

    data class Call(
        val number: String,
        val type: String,   // missed | rejected | answered | outgoing
        val startTime: Long, // epoch ms
        val duration: Long,
        val savedInPhone: Boolean = false // caller is in this phone's contacts
    )

    fun handle(context: Context, call: Call) {
        val cfg = Config(context)
        // Pull the latest rules + message from Media right now, so a change
        // made in Media applies to the very next call. Short timeout: if the
        // phone is offline we fall back to the last synced copy and still send.
        cfg.sync(timeoutMs = 6000)
        val s = cfg.effectiveSettings()

        // A call from this number just arrived, so any pending follow-up for
        // them is moot — cancel it. If we end up sending a reply below, a
        // fresh follow-up is scheduled afterwards.
        cancelFollowup(context, call.number)

        val eventId = "${cfg.deviceId()}:${call.number}:${call.startTime}"

        // ── Rule evaluation (mirrors missedCallWebhook exactly) ──
        var skipReason = ""
        // Master kill-switch: the dashboard can turn this specific device off
        // (or mark it inactive). That overrides every local rule.
        if (cfg.isConfigured() && !cfg.deviceRemotelyOn()) skipReason = "disabled_by_dashboard"
        else if (!s.optBoolean("enabled")) skipReason = "auto_sms_disabled"
        else if (call.type == "missed" && !s.optBoolean("triggerMissed")) skipReason = "missed_trigger_disabled"
        else if (call.type == "rejected" && !s.optBoolean("triggerRejected")) skipReason = "rejected_trigger_disabled"
        else if (call.type == "answered" && !s.optBoolean("triggerAnswered")) skipReason = "answered_call_no_trigger"
        else if (call.type == "outgoing" && !s.optBoolean("triggerOutgoing")) skipReason = "outgoing_trigger_disabled"

        // "All" only counts when it isn't contradicted by Unknown/Saved being set
        // differently (e.g. Saved contacts turned off while All was still on).
        val unknownOn = s.optBoolean("recipientUnknown")
        val contactsOn = s.optBoolean("recipientContacts")
        val allOn = s.optBoolean("recipientAll") && unknownOn == contactsOn
        if (skipReason.isEmpty() && !allOn) {
            val isContact = call.savedInPhone || cfg.cachedContacts().contains(call.number)
            val okUnknown = s.optBoolean("recipientUnknown") && !isContact
            val okContacts = s.optBoolean("recipientContacts") && isContact
            if (!okUnknown && !okContacts) skipReason = "recipient_condition_not_met"
        }

        if (skipReason.isEmpty() && s.optBoolean("businessHoursEnabled")) {
            val within = withinHours(s)
            val shouldSend = if (s.optBoolean("sendOutsideBusinessHours")) !within else within
            if (!shouldSend) skipReason = "outside_business_hours"
        }

        if (skipReason.isEmpty()) {
            // Cooldown comes from Media (0 = reply to every call).
            val cdMs = (s.optDouble("cooldownHours", 0.0) * 3600_000L).toLong()
            if (cdMs > 0) {
                val last = cfg.lastSentAt(call.number)
                if (last > 0 && (System.currentTimeMillis() - last) < cdMs)
                    skipReason = "cooldown_active"
            }
        }

        if (skipReason.isNotEmpty()) {
            report(cfg, eventId, call, "skipped", skipReason = skipReason)
            return
        }

        val message = render(cfg)
        val smsAllowed = s.optBoolean("adminSmsAllowed", true)
        val waAllowed = s.optBoolean("adminWhatsappAllowed", true)
        var outcome = "sent"
        var failure = ""
        if (!smsAllowed) {
            // Media admin turned SMS off for this business — log it as skipped.
            report(cfg, eventId, call, "skipped", skipReason = "disabled_by_admin")
        } else try {
            val mgr = getManager(context, cfg)
            val parts = mgr.divideMessage(message)
            if (parts.size == 1) mgr.sendTextMessage(call.number, null, message, null, null)
            else mgr.sendMultipartTextMessage(call.number, null, parts, null, null)
            cfg.markSent(call.number, System.currentTimeMillis())
            // Reply went out — if the follow-up is on, arm it. It fires only
            // if the caller hasn't called again by then (re-checked at send).
            if (cfg.localFollowupEnabled()) {
                scheduleFollowup(context, call.number, cfg.localFollowupHours())
            }
        } catch (e: Exception) {
            outcome = "failed"
            failure = (e.message ?: "Send error").take(200)
        }

        if (smsAllowed) report(cfg, eventId, call, outcome, message = message, failureReason = failure)

        // Optional WhatsApp copy of the reply (same rules already passed).
        // Skipped when the admin relay phone sends this business's WhatsApp instead.
        val viaRelay = s.optBoolean("whatsappViaRelay", false)
        if (cfg.localWhatsappEnabled() && waAllowed && !viaRelay) {
            val waMsg = message // exactly the same text as the SMS, from Media
            val waEventId = "${cfg.deviceId()}:${call.number}:whatsapp:${call.startTime}"
            // Queued and sent automatically; it is logged to the dashboard
            // only once the Send tap actually happens (WhatsAppSender.onSent).
            runCatching {
                WhatsAppSender.send(context, call.number, waMsg, cfg.localWhatsappBusiness(),
                    waEventId, call.startTime)
            }
        }
    }

    /** The exact reply text Media has set (used by the WhatsApp test buttons). */
    fun currentMessage(context: Context): String {
        val cfg = Config(context)
        cfg.sync(timeoutMs = 8000)
        return render(cfg)
    }

    /** Logs a WhatsApp reply that was really sent (called off the main thread). */
    fun reportWhatsappSent(context: Context, number: String, message: String, eventId: String, startTime: Long) {
        val cfg = Config(context)
        val id = eventId.ifEmpty { "${cfg.deviceId()}:$number:whatsapp:$startTime" }
        report(cfg, id, Call(number, "missed", startTime, 0L), "sent", message = message, channel = "whatsapp")
    }

    // ── Follow-up SMS scheduling (WorkManager) ──

    private fun followupWorkName(number: String) = "followup_$number"

    fun scheduleFollowup(context: Context, number: String, hours: Int) {
        val data = workDataOf("number" to number, "baseTime" to System.currentTimeMillis())
        val req = OneTimeWorkRequestBuilder<FollowupWorker>()
            .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
            .setInputData(data)
            .addTag("followup")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(followupWorkName(number), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelFollowup(context: Context, number: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(followupWorkName(number)) }
    }

    /**
     * Runs when a scheduled follow-up fires. Sends the follow-up SMS only if
     * the caller has NOT called (or been called) again since the original
     * reply — otherwise they already reconnected and no nudge is needed.
     */
    fun sendFollowupNow(context: Context, number: String, baseTime: Long) {
        val cfg = Config(context)
        if (!cfg.localFollowupEnabled()) return
        if (!cfg.effectiveSettings().optBoolean("enabled")) return
        // They reconnected in the meantime → skip.
        if (hasCallSince(context, number, baseTime)) return

        val message = renderFollowup(cfg)
        var outcome = "sent"
        var failure = ""
        try {
            val mgr = getManager(context, cfg)
            val parts = mgr.divideMessage(message)
            if (parts.size == 1) mgr.sendTextMessage(number, null, message, null, null)
            else mgr.sendMultipartTextMessage(number, null, parts, null, null)
            cfg.markSent(number, System.currentTimeMillis())
        } catch (e: Exception) {
            outcome = "failed"
            failure = (e.message ?: "Send error").take(200)
        }
        val eventId = "${cfg.deviceId()}:$number:followup:$baseTime"
        report(cfg, eventId, Call(number, "followup", System.currentTimeMillis(), 0L),
            outcome, message = message, failureReason = failure)
    }

    /** True if the call log has any call to/from [number] after [since] (epoch ms). */
    private fun hasCallSince(context: Context, number: String, since: Long): Boolean {
        val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE)
        val cur = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, proj,
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), null
            )
        }.getOrNull() ?: return false
        cur.use {
            while (it.moveToNext()) {
                val n = it.getString(0)?.replace(Regex("\\D"), "")?.takeLast(10) ?: continue
                if (n == number) return true
            }
        }
        return false
    }

    /**
     * Sends a one-off test SMS to a given number, bypassing every rule
     * (triggers, recipient condition, business hours, cooldown) and the
     * nativeCallLog report — this is a pure "does this phone's SIM actually
     * send SMS" check, so it never affects cooldowns or the dashboard's real
     * call/SMS history. Returns (success, message-or-error).
     */
    fun sendTest(context: Context, rawNumber: String): Pair<Boolean, String> {
        val number = rawNumber.replace(Regex("\\D"), "").takeLast(10)
        if (number.length < 10) return false to "Enter a valid 10-digit number."

        val cfg = Config(context)
        // Surface the most common cause plainly instead of a generic failure.
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false to ("SMS permission is OFF. Open app Settings → Permissions → SMS and allow it. " +
                "On Android 13+/Pixel, first tap the ⋮ menu on the app-info screen → “Allow restricted settings”, then allow SMS.")
        }
        cfg.sync(timeoutMs = 8000)
        val message = "[TEST] " + render(cfg)

        return try {
            val mgr = getManager(context, cfg)
            val parts = mgr.divideMessage(message)
            if (parts.size == 1) mgr.sendTextMessage(number, null, message, null, null)
            else mgr.sendMultipartTextMessage(number, null, parts, null, null)
            true to "Test SMS sent to $number."
        } catch (e: SecurityException) {
            false to "SMS blocked by the system (permission denied). Allow SMS in app settings; on Android 13+/Pixel also tap ⋮ → Allow restricted settings."
        } catch (e: Exception) {
            false to ("Send failed: " + (e.message ?: "unknown error") + ". Check the SIM has an SMS pack and the right SIM is picked above.")
        }
    }

    /**
     * Returns the SmsManager for whichever SIM the user picked in the app
     * (Config.simSubscriptionId), falling back to Android's system-default
     * SMS SIM if none was explicitly chosen (unchanged behavior for anyone
     * who hasn't opened the SIM picker).
     */
    private fun getManager(context: Context, cfg: Config): SmsManager {
        val defaultMgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else
            @Suppress("DEPRECATION") SmsManager.getDefault()
        val subId = cfg.simSubscriptionId()
        if (subId == -1) return defaultMgr
        return runCatching {
            @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)
        }.getOrDefault(defaultMgr)
    }

    private fun render(cfg: Config): String {
        val tpl = cfg.effectiveTemplate()
        val callback = cfg.effectiveCallback()
        val base = if (tpl.isNotEmpty()) tpl else (
            if (callback.isNotEmpty())
                "Thanks for calling {{businessName}}! We missed your call — call us back at {{callbackNumber}} and we'll get back to you shortly."
            else
                "Thanks for calling {{businessName}}! We missed your call — our team will get back to you shortly."
        )
        return substitute(cfg, base)
    }

    private fun renderFollowup(cfg: Config): String {
        val callback = cfg.effectiveCallback()
        val base = if (callback.isNotEmpty())
            "Hi, just following up on your recent call to {{businessName}}. We'd still love to help — please call us at {{callbackNumber}}."
        else
            "Hi, just following up on your recent call to {{businessName}}. We'd still love to help — please reach out when convenient."
        return substitute(cfg, base)
    }

    private fun substitute(cfg: Config, base: String): String {
        val businessName = cfg.effectiveBusinessName()
        val callback = cfg.effectiveCallback()
        val now = Date()
        return base
            .replace("{{businessName}}", businessName)
            .replace("{{callbackNumber}}", callback)
            .replace("{{businessPhone}}", callback)
            .replace("{{callerName}}", "")
            .replace("{{currentDate}}", SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(now))
            .replace("{{currentTime}}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
    }

    private fun withinHours(s: JSONObject): Boolean {
        val cal = Calendar.getInstance()
        val dayKeys = arrayOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        val today = dayKeys[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val days = s.optJSONArray("businessHoursDays")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.toSet()
        } ?: setOf("mon", "tue", "wed", "thu", "fri", "sat")
        if (!days.contains(today)) return false
        val start = (s.optString("businessHoursStart", "09:00")).split(":")
        val end = (s.optString("businessHoursEnd", "19:00")).split(":")
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = start[0].toInt() * 60 + start[1].toInt()
        val endMin = end[0].toInt() * 60 + end[1].toInt()
        return now in startMin..endMin
    }

    private fun report(
        cfg: Config, eventId: String, call: Call, outcome: String,
        message: String = "", skipReason: String = "", failureReason: String = "",
        channel: String = "sms"
    ) {
        runCatching {
            val body = JSONObject().apply {
                put("device", cfg.deviceId())
                put("token", cfg.token())
                put("eventId", eventId)
                put("callerNumber", call.number)
                put("callType", call.type)
                put("channel", channel)
                put("startTime", java.time.Instant.ofEpochMilli(call.startTime).toString())
                put("duration", call.duration)
                put("smsOutcome", outcome)
                if (message.isNotEmpty()) put("message", message)
                if (skipReason.isNotEmpty()) put("skipReason", skipReason)
                if (failureReason.isNotEmpty()) put("failureReason", failureReason)
            }
            val conn = (URL("${cfg.baseUrl()}/functions/nativeCallLog").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000
                doOutput = true; setRequestProperty("Content-Type", "application/json")
            }
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
