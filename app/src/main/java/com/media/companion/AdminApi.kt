package com.media.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the adminDevices backend function. Used only by the Admin
 * Control Centre, which the app shows when the server reports this device as
 * an admin. All calls carry this device's own id + token; the server checks
 * admin rights, so a non-admin phone gets nothing back.
 */
object AdminApi {

    data class Device(
        val id: String,
        val name: String,
        val phone: String,
        val business: String,
        val provider: String,
        val enabled: Boolean,
        val status: String,
        val lastSeenAt: String,
        val calls: Int,
        val sms: Int
    )

    private fun post(cfg: Config, body: JSONObject, timeoutMs: Int = 15000): JSONObject? {
        if (!cfg.isConfigured()) return null
        val url = "${cfg.baseUrl()}/functions/adminDevices"
        body.put("device", cfg.deviceId())
        body.put("token", cfg.token())
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = timeoutMs; readTimeout = timeoutMs
                doOutput = true; setRequestProperty("Content-Type", "application/json")
            }
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val raw = BufferedReader(InputStreamReader(stream)).readText()
                JSONObject(raw)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Returns the device list, or null on error. */
    fun list(cfg: Config): List<Device>? {
        val json = post(cfg, JSONObject().put("action", "list")) ?: return null
        if (!json.optBoolean("success")) return null
        val arr: JSONArray = json.optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Device(
                id = d.optString("id"),
                name = d.optString("deviceName"),
                phone = d.optString("phoneNumber"),
                business = d.optString("businessName"),
                provider = d.optString("provider"),
                enabled = d.optBoolean("autoSmsEnabled", true),
                status = d.optString("status", "active"),
                lastSeenAt = d.optString("lastSeenAt"),
                calls = d.optInt("totalCallsReceived"),
                sms = d.optInt("totalSmsSent")
            )
        }
    }

    /** Turns a device's auto-reply on/off. Returns true on success. */
    fun setEnabled(cfg: Config, targetDeviceId: String, enabled: Boolean): Boolean {
        val json = post(
            cfg,
            JSONObject().put("action", "setEnabled").put("targetDeviceId", targetDeviceId).put("enabled", enabled)
        ) ?: return false
        return json.optBoolean("success")
    }
}
