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