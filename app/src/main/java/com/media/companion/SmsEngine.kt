package com.media.companion

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
        // Make sure we have rules; if never synced, try once now.
        if (cfg.cachedSettings() == null) cfg.sync()
        val s = cfg.cachedSettings() ?: JSONObject()

        val eventId = "${cfg.deviceId()}:${call.number}:${call.startTime}"

        if (call.type == "outgoing") {
            report(cfg, eventId, call, "skipped", skipReason = "outgoing_call")
            return
        }

        // ── Rule evaluation (mirrors missedCallWebhook exactly) ──
        var skipReason = ""
        if (!s.optBoolean("enabled")) skipReason = "auto_sms_disabled"
        else if (call.type == "missed" && !s.optBoolean("triggerMissed")) skipReason = "missed_trigger_disabled"
        else if (call.type == "rejected" && !s.optBoolean("triggerRejected")) skipReason = "rejected_trigger_disabled"
        else if (call.type == "answered" && !s.optBoolean("triggerAnswered")) skipReason = "answered_call_no_trigger"

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
            val mgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                context.getSystemService(SmsManager::class.java)
            else
                @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = mgr.divideMessage(message)
            if (parts.size == 1) mgr.sendTextMessage(call.number, null, message, null, null)
            else mgr.sendMultipartTextMessage(call.number, null, parts, null, null)
            cfg.markSent(call.number, System.currentTimeMillis())
        } catch (e: Exception) {
            outcome = "failed"
            failure = (e.message ?: "Send error").take(200)
        }

        report(cfg, eventId, call, outcome, message = message, failureReason = failure)
    }

    private fun render(cfg: Config): String {
        val tpl = cfg.cachedTemplate() ?: ""
        val businessName = cfg.businessName()
        val callback = cfg.callbackNumber().ifEmpty { cfg.devicePhone() }
        val base = if (tpl.isNotEmpty()) tpl else (
            if (callback.isNotEmpty())
                "Thanks for calling {{businessName}}! We missed your call — call us back at {{callbackNumber}} and we'll get back to you shortly."
            else
                "Thanks for calling {{businessName}}! We missed your call — our team will get back to you shortly."
        )
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
        message: String = "", skipReason: String = "", failureReason: String = ""
    ) {
        runCatching {
            val body = JSONObject().apply {
                put("device", cfg.deviceId())
                put("token", cfg.token())
                put("eventId", eventId)
                put("callerNumber", call.number)
                put("callType", call.type)
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