package com.media.companion

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Holds the pairing config (App URL, Device ID, Token) and the cached
 * synced rules/template/contacts pulled from nativeDeviceSync.
 *
 * Everything the phone needs to decide-and-send a missed-call SMS ENTIRELY
 * locally is cached here — so a call can be detected and texted even if the
 * phone is briefly offline at that exact moment (sending SMS itself never
 * needs internet; only this sync and the later nativeCallLog report do).
 */
class Config(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("media_companion", Context.MODE_PRIVATE)

    fun baseUrl(): String = prefs.getString("baseUrl", "https://festive-post-flow.base44.app") ?: ""
    fun deviceId(): String = prefs.getString("deviceId", "") ?: ""
    fun token(): String = prefs.getString("token", "") ?: ""
    fun isConfigured(): Boolean = deviceId().isNotEmpty() && token().isNotEmpty()

    // ── Whether the business owner has explicitly agreed to the on-device
    // consent screen that discloses exactly what this app reads and sends,
    // before any OS permission dialog is shown. Required before requestPerms()
    // or the monitor service may start.
    fun consentGiven(): Boolean = prefs.getBoolean("consentGiven", false)
    fun setConsentGiven(v: Boolean) { prefs.edit().putBoolean("consentGiven", v).apply() }

    // ── Which SIM to send from on a dual-SIM phone. -1 = use Android's
    // system default SMS SIM; otherwise an explicit subscriptionId, so the
    // app never silently relies on whichever SIM the phone happens to have
    // set as default (which can be the wrong/empty-balance one).
    fun simSubscriptionId(): Int = prefs.getInt("simSubId", -1)
    fun setSimSubscriptionId(id: Int) { prefs.edit().putInt("simSubId", id).apply() }

    // ── On-device rules (set from the app's own screen) ──
    // These let the business owner configure WHEN to reply, WHO gets the SMS
    // and the MESSAGE text directly on the phone, without needing the
    // dashboard. Once saved, they take precedence over whatever the server
    // sync returned; business hours and cooldown (not exposed on-device) are
    // still taken from the synced settings when present.
    fun localRulesSet(): Boolean = prefs.getBoolean("l_set", false)
    fun localEnabled(): Boolean = prefs.getBoolean("l_enabled", true)
    fun localTrigMissed(): Boolean = prefs.getBoolean("l_missed", true)
    fun localTrigRejected(): Boolean = prefs.getBoolean("l_rejected", true)
    fun localTrigAnswered(): Boolean = prefs.getBoolean("l_answered", false)
    fun localTrigOutgoing(): Boolean = prefs.getBoolean("l_outgoing", false)
    fun localRecipientMode(): Int = prefs.getInt("l_recip", 0) // 0 everyone, 1 unknown-only, 2 contacts-only
    fun localMessage(): String = prefs.getString("l_msg", "") ?: ""
    fun localBusinessName(): String = prefs.getString("l_biz", "") ?: ""
    fun localCallback(): String = prefs.getString("l_cb", "") ?: ""

    // ── Optional follow-up SMS: if the caller hasn't called again within N
    // hours of the first auto-reply, send one more nudge. Off by default.
    fun localFollowupEnabled(): Boolean = prefs.getBoolean("l_fu", false)
    fun localFollowupHours(): Int = prefs.getInt("l_fu_h", 12).coerceIn(1, 168)
    fun localFollowupMessage(): String = prefs.getString("l_fu_msg", "") ?: ""

    // ── Optional WhatsApp auto-reply (Accessibility-driven). Off by default.
    // Sends from the phone's own WhatsApp by automating its UI; needs the
    // Accessibility service enabled, otherwise it falls back to a one-tap
    // notification. See WhatsAppSender / WhatsAppAutoSendService.
    fun localWhatsappEnabled(): Boolean = prefs.getBoolean("l_wa", false)
    fun localWhatsappBusiness(): Boolean = prefs.getBoolean("l_wa_biz", false)
    fun localWhatsappMessage(): String = prefs.getString("l_wa_msg", "") ?: ""

    fun saveWhatsapp(enabled: Boolean, useBusiness: Boolean, message: String) {
        prefs.edit()
            .putBoolean("l_wa", enabled)
            .putBoolean("l_wa_biz", useBusiness)
            .putString("l_wa_msg", message)
            .apply()
    }

    fun saveRules(
        enabled: Boolean, missed: Boolean, rejected: Boolean, answered: Boolean,
        outgoing: Boolean, recipientMode: Int, message: String,
        businessName: String, callback: String,
        followupEnabled: Boolean, followupHours: Int, followupMessage: String
    ) {
        prefs.edit()
            .putBoolean("l_set", true)
            .putBoolean("l_enabled", enabled)
            .putBoolean("l_missed", missed)
            .putBoolean("l_rejected", rejected)
            .putBoolean("l_answered", answered)
            .putBoolean("l_outgoing", outgoing)
            .putInt("l_recip", recipientMode)
            .putString("l_msg", message)
            .putString("l_biz", businessName)
            .putString("l_cb", callback)
            .putBoolean("l_fu", followupEnabled)
            .putInt("l_fu_h", followupHours.coerceIn(1, 168))
            .putString("l_fu_msg", followupMessage)
            .apply()
    }

    fun save(base: String, dev: String, tok: String) {
        prefs.edit()
            .putString("baseUrl", base.ifEmpty { "https://festive-post-flow.base44.app" }.trimEnd('/'))
            .putString("deviceId", dev)
            .putString("token", tok)
            .apply()
    }

    // ── Cached synced config ──
    fun cachedSettings(): JSONObject? {
        val s = prefs.getString("settings", null) ?: return null
        return runCatching { JSONObject(s) }.getOrNull()
    }
    fun cachedTemplate(): String? = prefs.getString("templateMessage", null)
    fun cachedContacts(): Set<String> = prefs.getStringSet("contacts", emptySet()) ?: emptySet()
    fun businessName(): String = prefs.getString("businessName", "us") ?: "us"
    fun callbackNumber(): String = prefs.getString("callbackNumber", "") ?: ""
    fun devicePhone(): String = prefs.getString("devicePhone", "") ?: ""

    // ── Effective config = on-device rules layered over synced settings ──
    // The engine reads these so a phone configured entirely on-device works
    // with no dashboard at all, while a phone that also syncs still gets
    // business-hours / cooldown from the server.
    fun effectiveSettings(): JSONObject {
        val base = cachedSettings() ?: JSONObject()
        if (!localRulesSet()) return base
        base.put("enabled", localEnabled())
        base.put("triggerMissed", localTrigMissed())
        base.put("triggerRejected", localTrigRejected())
        base.put("triggerAnswered", localTrigAnswered())
        base.put("triggerOutgoing", localTrigOutgoing())
        base.put("recipientAll", localRecipientMode() == 0)
        base.put("recipientUnknown", localRecipientMode() == 1)
        base.put("recipientContacts", localRecipientMode() == 2)
        return base
    }

    fun effectiveTemplate(): String {
        val l = localMessage()
        if (localRulesSet() && l.isNotBlank()) return l
        return cachedTemplate() ?: ""
    }

    fun effectiveBusinessName(): String {
        val l = localBusinessName()
        if (localRulesSet() && l.isNotBlank()) return l
        return businessName()
    }

    fun effectiveCallback(): String {
        val l = localCallback()
        if (localRulesSet() && l.isNotBlank()) return l
        return callbackNumber().ifEmpty { devicePhone() }
    }

    // ── Cooldown tracking (per caller number) ──
    fun lastSentAt(number: String): Long = prefs.getLong("cd_$number", 0L)
    fun markSent(number: String, ts: Long) { prefs.edit().putLong("cd_$number", ts).apply() }

    // ── Last processed call-log timestamp (prevents re-processing history) ──
    var lastCallTs: Long
        get() = prefs.getLong("lastCallTs", 0L)
        set(v) { prefs.edit().putLong("lastCallTs", v).apply() }

    /** Pulls rules + template + contacts from nativeDeviceSync. Returns true on success. */
    fun sync(): Boolean {
        if (!isConfigured()) return false
        val url = "${baseUrl()}/functions/nativeDeviceSync?device=${deviceId()}&token=${token()}"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
            }
            try {
                val raw = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(raw)
                if (!json.optBoolean("success")) return false
                val s = json.optJSONObject("settings") ?: JSONObject()
                val tpl = json.optJSONObject("template")
                val biz = json.optJSONObject("business") ?: JSONObject()
                val dev = json.optJSONObject("device") ?: JSONObject()
                val contacts: Set<String> = json.optJSONArray("savedContacts")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.toSet()
                } ?: emptySet()
                prefs.edit()
                    .putString("settings", s.toString())
                    .putString("templateMessage", tpl?.optString("message") ?: "")
                    .putString("businessName", biz.optString("businessName"))
                    .putString("callbackNumber", s.optString("callbackNumber"))
                    .putString("devicePhone", dev.optString("phoneNumber"))
                    .putStringSet("contacts", contacts)
                    .apply()
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
