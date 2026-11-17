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
    companion object { const val DEFAULT_URL = "https://media-agency.in" }

    private val prefs = ctx.getSharedPreferences("media_companion", Context.MODE_PRIVATE)

    // The agency's own domain. Phones paired with the old base44.app address
    // are moved over automatically (both point at the same backend).
    fun baseUrl(): String {
        val v = prefs.getString("baseUrl", DEFAULT_URL) ?: DEFAULT_URL
        return if (v.contains("base44.app")) DEFAULT_URL else v
    }
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

    // ── Rules + message are owned by the Media web app ──
    // Triggers (missed / rejected / answered / outgoing), recipients, cooldown
    // and the message text are all set in Media and pulled by sync(). The
    // phone no longer has its own copy, so there is exactly one place to
    // change them and Media's switches can never be silently overridden.

    // ── Optional follow-up SMS: if the caller hasn't called again within N
    // hours of the first auto-reply, send one more nudge. Off by default.
    fun localFollowupEnabled(): Boolean = prefs.getBoolean("l_fu", false)
    fun localFollowupHours(): Int = prefs.getInt("l_fu_h", 12).coerceIn(1, 168)

    // ── Optional WhatsApp auto-reply (Accessibility-driven). Off by default.
    // Sends from the phone's own WhatsApp by automating its UI; needs the
    // Accessibility service enabled, otherwise it falls back to a one-tap
    // notification. See WhatsAppSender / WhatsAppAutoSendService.
    fun localWhatsappEnabled(): Boolean = prefs.getBoolean("l_wa", false)
    fun localWhatsappBusiness(): Boolean = prefs.getBoolean("l_wa_biz", false)

    fun saveWhatsapp(enabled: Boolean, useBusiness: Boolean) {
        prefs.edit()
            .putBoolean("l_wa", enabled)
            .putBoolean("l_wa_biz", useBusiness)
            .apply()
    }

    fun saveFollowup(enabled: Boolean, hours: Int) {
        prefs.edit()
            .putBoolean("l_fu", enabled)
            .putInt("l_fu_h", hours.coerceIn(1, 168))
            .apply()
    }

    fun save(base: String, dev: String, tok: String) {
        prefs.edit()
            .putString("baseUrl", base.ifEmpty { DEFAULT_URL }.trimEnd('/').let { if (it.contains("base44.app")) DEFAULT_URL else it })
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

    // ── Remote on/off from the dashboard (per device). The dashboard's
    // "Disable Auto SMS" / inactive status is a hard master switch that the
    // app must obey. Defaults to enabled so a never-synced/offline device
    // keeps working (fail-open) until a sync says otherwise.
    fun deviceRemoteEnabled(): Boolean = prefs.getBoolean("deviceEnabled", true)
    fun deviceStatus(): String = prefs.getString("deviceStatus", "active") ?: "active"
    fun deviceRemotelyOn(): Boolean = deviceRemoteEnabled() && deviceStatus() == "active"

    // ── Token revoked by the Media admin ──
    // When Media flips this phone's token, the old one is refused (HTTP 401).
    // From then on the phone sends NOTHING until a sync succeeds with a valid
    // token. The flag survives clearing the Device ID / Token fields, so
    // blanking them is not a way round the block. It is only ever set for a
    // phone that had paired successfully before, so a typo during first-time
    // setup does not lock anyone out.
    fun tokenRejected(): Boolean = prefs.getBoolean("tokenRejected", false)

    // ── Effective config = exactly what Media synced ──
    // If the phone has never synced, fall back to the same defaults the
    // server uses for a business that never saved its rules.
    fun effectiveSettings(): JSONObject {
        cachedSettings()?.let { return it }
        return JSONObject().apply {
            put("enabled", true)
            put("triggerMissed", true); put("triggerRejected", true)
            put("triggerAnswered", false); put("triggerOutgoing", false)
            put("recipientAll", true); put("recipientUnknown", false); put("recipientContacts", false)
            put("businessHoursEnabled", false)
            put("cooldownHours", 6)
        }
    }

    fun effectiveTemplate(): String = cachedTemplate() ?: ""

    /** Updates the cached copy right away so the next call uses the new switch even before sync. */
    fun putCachedSetting(key: String, value: Any) {
        val s = effectiveSettings()
        s.put(key, value)
        prefs.edit().putString("settings", s.toString()).apply()
    }

    /** Saves call-type switches to Media (nativeUpdateRules). Returns true on success. */
    fun pushRules(values: Map<String, Any>): Boolean {
        if (!isConfigured()) return false
        return runCatching {
            val body = JSONObject().apply {
                put("device", deviceId()); put("token", token())
                values.forEach { (k, v) -> put(k, v) }
            }
            val conn = (URL("${baseUrl()}/functions/nativeUpdateRules").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000
                doOutput = true; setRequestProperty("Content-Type", "application/json")
            }
            try {
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode in 200..299
            } finally { conn.disconnect() }
        }.getOrDefault(false)
    }
    fun effectiveBusinessName(): String = businessName()
    fun effectiveCallback(): String = callbackNumber().ifEmpty { devicePhone() }
    fun lastSyncAt(): Long = prefs.getLong("lastSyncAt", 0L)

    // ── Admin control centre ──
    // True only when Media's last sync said this phone belongs to an
    // admin-role account. This just decides whether the button is SHOWN; the
    // server re-checks the role on every adminDevices call, so it is not
    // something a modified app can talk its way past.
    fun isAdmin(): Boolean = prefs.getBoolean("isAdmin", false)

    /** This phone is the admin WhatsApp relay phone (chosen in the Admin Control Centre). */
    fun isRelay(): Boolean = prefs.getBoolean("isRelay", false)

    /** POSTs to adminDevices with this phone's own device + token. Returns the parsed JSON (also for 4xx/5xx error bodies), or null if unreachable. */
    fun adminCall(values: Map<String, Any>, function: String = "adminDevices"): JSONObject? {
        if (!isConfigured()) return null
        return runCatching {
            val body = JSONObject().apply {
                put("device", deviceId()); put("token", token())
                values.forEach { (k, v) -> put(k, v) }
            }
            val conn = (URL("${baseUrl()}/functions/$function").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000
                doOutput = true; setRequestProperty("Content-Type", "application/json")
            }
            try {
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                JSONObject(BufferedReader(InputStreamReader(stream)).readText())
            } finally { conn.disconnect() }
        }.getOrNull()
    }

    // ── Ring tracking (to tell incoming from outgoing on any phone) ──
    var ringTrackingSince: Long
        get() = prefs.getLong("ringSince", 0L)
        set(v) { prefs.edit().putLong("ringSince", v).apply() }

    fun recordRing(ts: Long) {
        val list = (prefs.getString("rings", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { ts - it < 24 * 3600_000L }.takeLast(60) + ts
        prefs.edit().putString("rings", list.joinToString(",")).apply()
    }

    /** Ring tracking is proven to work on this phone (at least one ring recorded). */
    fun ringTrackingWorks(): Boolean =
        (prefs.getString("rings", "") ?: "").split(',').any { it.toLongOrNull() != null }

    /** True if the phone rang within a minute before / 20 s after [callStart]. */
    fun rangNear(callStart: Long): Boolean =
        (prefs.getString("rings", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .any { it in (callStart - 60_000L)..(callStart + 20_000L) }

    // ── Cooldown tracking (per caller number) ──
    fun lastSentAt(number: String): Long = prefs.getLong("cd_$number", 0L)
    fun markSent(number: String, ts: Long) { prefs.edit().putLong("cd_$number", ts).apply() }

    // ── Last processed call-log timestamp (prevents re-processing history) ──
    var lastCallTs: Long
        get() = prefs.getLong("lastCallTs", 0L)
        set(v) { prefs.edit().putLong("lastCallTs", v).apply() }

    /** Pulls rules + template + contacts from nativeDeviceSync. Returns true on success. */
    fun sync(timeoutMs: Int = 15000): Boolean {
        if (!isConfigured()) return false
        val url = "${baseUrl()}/functions/nativeDeviceSync?device=${deviceId()}&token=${token()}"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = timeoutMs; readTimeout = timeoutMs
            }
            try {
                if (conn.responseCode == 401) {
                    if (lastSyncAt() > 0L) prefs.edit().putBoolean("tokenRejected", true).apply()
                    return false
                }
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
                    .putBoolean("deviceEnabled", dev.optBoolean("autoSmsEnabled", true))
                    .putString("deviceStatus", dev.optString("status", "active"))
                    .putBoolean("isAdmin", dev.optBoolean("isAdmin", false))
                    .putBoolean("isRelay", dev.optBoolean("isRelay", false))
                    .putStringSet("contacts", contacts)
                    .putLong("lastSyncAt", System.currentTimeMillis())
                    .putBoolean("tokenRejected", false)
                    .apply()
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
