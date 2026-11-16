package com.media.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs ONLY on the admin phone chosen as the WhatsApp relay (Config.isRelay()).
 *
 * Every few seconds it asks Media for the next WhatsApp job (whatsappRelay →
 * claim). The server decides if one may go out — it enforces the pause switch,
 * the hourly cap and the minimum gap — so a modified app cannot send faster
 * than the admin allows. The message is then sent through the normal
 * WhatsAppSender queue, and the outcome is reported back to the server.
 *
 * One job at a time. A job is "in flight" from the moment it is claimed until
 * it is reported:
 *   - sent    → WhatsAppSender.onSent marks it (markSent) → reported as sent
 *   - waiting → still in the sender queue (e.g. phone locked) → keep waiting
 *   - gone    → left the queue without a send → reported as failed
 */
object RelayWorker {

    const val PREFIX = "relay:"
    private const val PREFS = "media_relay"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(c: Context): JSONArray =
        runCatching { JSONArray(prefs(c).getString("inflight", "[]")) }.getOrDefault(JSONArray())

    private fun save(c: Context, a: JSONArray) { prefs(c).edit().putString("inflight", a.toString()).apply() }

    fun markSent(c: Context, jobId: String) {
        val a = load(c)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == jobId) o.put("sent", true)
        }
        save(c, a)
    }

    /** Short text for the admin screen / status. */
    fun lastNote(c: Context): String = prefs(c).getString("note", "") ?: ""

    private fun note(c: Context, text: String) {
        val t = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        prefs(c).edit().putString("note", "$t — $text").apply()
    }

    /** Called every ~20 s from CallMonitorService, on a background thread. */
    @Synchronized
    fun tick(context: Context) {
        val app = context.applicationContext
        val cfg = Config(app)
        if (!cfg.isRelay() || !cfg.isConfigured()) return

        // 1) Report finished jobs.
        val kept = JSONArray()
        val inflight = load(app)
        for (i in 0 until inflight.length()) {
            val o = inflight.getJSONObject(i)
            val id = o.optString("id")
            when {
                o.optBoolean("sent") -> {
                    val r = cfg.adminCall(mapOf("action" to "report", "jobId" to id, "outcome" to "sent"), "whatsappRelay")
                    if (r?.optBoolean("success", false) != true) kept.put(o) // retry next tick
                    else note(app, "Sent a job ✓")
                }
                WhatsAppSender.isQueuedEvent(app, PREFIX + id) || WhatsAppSender.hasPending() -> kept.put(o)
                else -> {
                    val why = WhatsAppSender.lastResult(app).ifBlank { "WhatsApp did not send." }.take(250)
                    val r = cfg.adminCall(mapOf("action" to "report", "jobId" to id, "outcome" to "failed", "reason" to why), "whatsappRelay")
                    if (r?.optBoolean("success", false) != true) kept.put(o)
                    else note(app, "Job failed: $why")
                }
            }
        }
        save(app, kept)
        if (kept.length() > 0) return // one job at a time

        // 2) Ask for the next job (server enforces pause + rate limits).
        val res = cfg.adminCall(mapOf("action" to "claim"), "whatsappRelay") ?: run {
            note(app, "Can't reach Media"); return
        }
        if (res.has("error")) { note(app, res.optString("error")); return }
        val job = res.optJSONObject("job")
        if (job == null) {
            note(app, if (res.optBoolean("paused")) "Relay is paused" else "Waiting for jobs")
            return
        }
        val id = job.optString("id")
        val number = job.optString("number")
        val message = job.optString("message")
        if (id.isBlank() || number.isBlank() || message.isBlank()) return

        val a = load(app)
        a.put(JSONObject().put("id", id).put("sent", false))
        save(app, a)
        note(app, "Sending to $number…")
        WhatsAppSender.send(app, number, message, cfg.localWhatsappBusiness(), PREFIX + id, System.currentTimeMillis())
    }
}
