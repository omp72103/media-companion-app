package com.media.companion

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONObject

/**
 * Admin Control Centre.
 *
 * Lists every phone paired to Media and lets an admin switch, per phone:
 *   - ALL automatic messaging (master switch)
 *   - SMS only
 *   - WhatsApp only
 *
 * Every call goes to the adminDevices endpoint, which re-checks on the server
 * that this phone belongs to an admin-role account. Hiding the button in the
 * main screen is only cosmetic; this screen is not the security boundary.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)

        if (!cfg.isAdmin()) {
            Toast.makeText(this, "Admin access only.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        title = "Admin Control Centre"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Admin Control Centre"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Turn automatic messages on or off for any paired phone. Changes apply the next time that phone syncs."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        val refresh = MaterialButton(this).apply {
            text = "Refresh list"
            setOnClickListener { load() }
        }
        root.addView(refresh)

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(statusText)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        setContentView(ScrollView(this).apply { addView(root) })
        load()
    }

    private fun load() {
        statusText.text = "Loading phones…"
        Thread {
            val res = cfg.adminCall(mapOf("action" to "list"))
            runOnUiThread {
                if (res == null) {
                    statusText.text = "Could not reach the server. Check the internet and tap Refresh."
                    return@runOnUiThread
                }
                if (!res.optBoolean("success", true) || res.has("error")) {
                    statusText.text = "⛔ " + res.optString("error", "Request failed.")
                    return@runOnUiThread
                }
                val arr = res.optJSONArray("devices")
                listBox.removeAllViews()
                if (arr == null || arr.length() == 0) {
                    statusText.text = "No phones are paired yet."
                    return@runOnUiThread
                }
                statusText.text = "${arr.length()} phone(s)"
                for (i in 0 until arr.length()) listBox.addView(card(arr.getJSONObject(i)))
            }
        }.start()
    }

    private fun card(d: JSONObject): View {
        val id = d.optString("id")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val name = d.optString("businessName").ifBlank { d.optString("deviceName", "Unnamed phone") }
        box.addView(TextView(this).apply {
            text = name
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        val phone = d.optString("phoneNumber").ifBlank { "no number" }
        val seen = d.optString("lastSeenAt").ifBlank { "never" }
        box.addView(TextView(this).apply {
            text = "$phone · ${d.optString("deviceName")}\nLast seen: $seen\n" +
                "Calls: ${d.optInt("totalCallsReceived")} · SMS sent: ${d.optInt("totalSmsSent")}"
            textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
        })

        box.addView(toggle("All automatic messages", d.optBoolean("autoSmsEnabled", true)) { on, revert ->
            send(mapOf("action" to "setEnabled", "targetDeviceId" to id, "enabled" to on), revert)
        })
        box.addView(toggle("SMS allowed", d.optBoolean("adminSmsAllowed", true)) { on, revert ->
            send(mapOf("action" to "setChannel", "targetDeviceId" to id, "channel" to "sms", "enabled" to on), revert)
        })
        box.addView(toggle("WhatsApp allowed", d.optBoolean("adminWhatsappAllowed", true)) { on, revert ->
            send(mapOf("action" to "setChannel", "targetDeviceId" to id, "channel" to "whatsapp", "enabled" to on), revert)
        })
        return box
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean, () -> Unit) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = MaterialSwitch(this)
        sw.isChecked = initial
        var reverting = false
        sw.setOnCheckedChangeListener { _, checked ->
            if (reverting) return@setOnCheckedChangeListener
            onChange(checked) {
                reverting = true
                sw.isChecked = !checked
                reverting = false
            }
        }
        row.addView(sw)
        return row
    }

    /** Sends one admin action; if it fails, [revert] flips the switch back so the screen never lies. */
    private fun send(values: Map<String, Any>, revert: () -> Unit) {
        Thread {
            val res = cfg.adminCall(values)
            val ok = res != null && res.optBoolean("success", false)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                } else {
                    revert()
                    val why = res?.optString("error").orEmpty().ifBlank { "Could not reach the server." }
                    Toast.makeText(this, "Not saved: $why", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
