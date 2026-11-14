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
        val duration: Long
    )

    fun handle(context: Context, call: Call) {
        val cfg = Config(context)
        // Make sure we have rules; if the phone was never configured on-device
        // and never synced, try a sync once now. When on-device rules are set,
        // they define everything needed and no server is required.
        if (cfg.cachedSettings() == null && !cfg.localRulesSet()) cfg.sync()
        val s = cfg.effectiveSettings()

        // A call from this number just arrived, so any pending follow-up for
        // them is moot — cancel it. If we end up sending a reply below, a
        // fresh follow-up is scheduled afterwards.
        cancelFollowup(context, call.number)

        val eventId = "${cfg.deviceId()}:${call.number}:${call.startTime}"

        // ── Rule evaluation (mirrors missedCallWebhook exactly) ──
        var skipReason = ""
        if (!s.optBoolean("enabled")) skipReason = "auto_sms_disabled"
        else if (call.type == "missed" && !s.optBoolean("triggerMissed")) skipReason = "missed_trigger_disabled"
        else if (call.type == "rejected" && !s.optBoolean("triggerRejected")) skipReason = "rejected_trigger_disabled"
        else if (call.type == "answered" && !s.optBoolean("triggerAnswered")) skipReason = "answered_call_no_trigger"
        else if (call.type == "outgoing" && !s.optBoolean("triggerOutgoing")) skipReason = "outgoing_trigger_disabled"

        if (skipReason.isEmpty() && !s.optBoolean("recipientAll")) {
            val isContact = cfg.cachedContacts().contains(call.number)
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
            val cdHours = (if (s.has("cooldownHours")) s.optInt("cooldownHours") else 6).toLong()
            if (cdHours > 0) {
                val last = cfg.lastSentAt(call.number)
                if (last > 0 && (System.currentTimeMillis() - last) < cdHours * 3600_000L)
                    skipReason = "cooldown_active"
            }
        }

        if (skipReason.isNotEmpty()) {
            report(cfg, eventId, call, "skipped", skipReason = skipReason)
            return
        }

        val message = render(cfg)
        var outcome = "sent"
        var failure = ""
        try {
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

        report(cfg, eventId, call, outcome, message = message, failureReason = failure)

        // Optional WhatsApp copy of the reply (same rules already passed).
        if (cfg.localWhatsappEnabled()) {
            val waMsg = renderWhatsapp(cfg)
            val auto = WhatsAppSender.isAccessibilityEnabled(context)
            runCatching {
                WhatsAppSender.send(context, call.number, waMsg, cfg.localWhatsappBusiness())
            }
            // Log auto-sent WhatsApp replies to the dashboard. The one-tap
            // fallback is a manual action, so it isn't logged as sent here.
            if (auto) {
                val waEventId = "${cfg.deviceId()}:${call.number}:whatsapp:${call.startTime}"
                report(cfg, waEventId, call, "sent", message = waMsg, channel = "whatsapp")
            }
        }
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
        if (cfg.cachedSettings() == null && !cfg.localRulesSet()) cfg.sync()
        val message = "[TEST] " + render(cfg)

        return try {
            val mgr = getManager(context, cfg)
            val parts = mgr.divideMessage(message)
            if (parts.size == 1) mgr.sendTextMessage(number, null, message, null, null)
            else mgr.sendMultipartTextMessage(number, null, parts, null, null)
            true to "Test SMS sent to $number."
        } catch (e: Exception) {
            false to ("Send failed: " + (e.message ?: "unknown error"))
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
        val tpl = cfg.localFollowupMessage()
        val callback = cfg.effectiveCallback()
        val base = if (tpl.isNotEmpty()) tpl else (
            if (callback.isNotEmpty())
                "Hi, just following up on your recent call to {{businessName}}. We'd still love to help — please call us at {{callbackNumber}}."
            else
                "Hi, just following up on your recent call to {{businessName}}. We'd still love to help — please reach out when convenient."
        )
        return substitute(cfg, base)
    }

    private fun renderWhatsapp(cfg: Config): String {
        val tpl = cfg.localWhatsappMessage()
        // Fall back to the SMS reply text when no WhatsApp-specific text is set.
        if (tpl.isNotEmpty()) return substitute(cfg, tpl)
        return render(cfg)
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
