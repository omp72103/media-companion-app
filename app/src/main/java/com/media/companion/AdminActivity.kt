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
    private lateinit var relayBox: LinearLayout

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
            setOnClickListener { load(); loadRelay() }
        }
        root.addView(refresh)

        relayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(relayBox)

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(statusText)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        setContentView(ScrollView(this).apply { addView(root) })
        load()
        loadRelay()
    }

    // ── WhatsApp relay: this admin phone sends WhatsApp for businesses switched to "via relay" ──

    private fun loadRelay() {
        relayBox.removeAllViews()
        relayBox.addView(TextView(this).apply {
            text = "WhatsApp sending phones"; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        val info = TextView(this).apply { textSize = 12f; text = "Loading…"; setPadding(0, dp(2), 0, dp(6)) }
        relayBox.addView(info)
        Thread {
            val res = cfg.adminCall(mapOf("action" to "status"), "whatsappRelay")
            runOnUiThread {
                if (res == null || res.has("error")) {
                    info.text = res?.optString("error").orEmpty().ifBlank { "Could not reach the server." }
                    return@runOnUiThread
                }
                val c = res.getJSONObject("config")
                val mine = res.optBoolean("isRelayPhone")
                val counts = res.optJSONObject("counts")
                val phones = res.optJSONArray("phones")
                val sb = StringBuilder()
                sb.append("Waiting: ${counts?.optInt("queued") ?: 0} · Sent in the last hour: ${counts?.optInt("sentLastHour") ?: 0}\n")
                sb.append("Limits (per phone): ${c.optInt("maxPerHour")}/hour, at least ${c.optInt("minDelaySeconds")}s apart.\n")
                if (phones == null || phones.length() == 0) sb.append("\nNo sending phone yet.")
                else {
                    sb.append("\nSending phones (any of them can take the next message):")
                    for (i in 0 until phones.length()) {
                        val p = phones.getJSONObject(i)
                        sb.append("\n • ${p.optString("deviceName")} — ${if (p.optBoolean("online")) "ONLINE" else "OFFLINE"}, sent ${p.optInt("sentLastHour")} in the last hour")
                    }
                }
                info.text = sb.toString()

                // Is THIS phone ready to send? Say exactly what is missing.
                val accOn = WhatsAppSender.isAccessibilityEnabled(this)
                val pkg = if (cfg.localWhatsappBusiness()) "com.whatsapp.w4b" else "com.whatsapp"
                val waInstalled = runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
                relayBox.addView(TextView(this).apply {
                    textSize = 12f; setPadding(0, dp(6), 0, dp(2))
                    text = "This phone: " + (if (mine) "SENDING" else "not a sending phone") +
                        "\n" + (if (accOn) "✔" else "✘") + " Accessibility (needed to press Send)" +
                        "\n" + (if (waInstalled) "✔" else "✘") + " ${if (pkg.endsWith("w4b")) "WhatsApp Business" else "WhatsApp"} installed" +
                        "\n✔ Must have NO Google Pay / UPI apps"
                })
                if (!accOn) {
                    relayBox.addView(MaterialButton(this).apply {
                        text = "Open Accessibility settings"
                        setOnClickListener { WhatsAppSender.openAccessibilitySettings(this@AdminActivity) }
                    })
                }
                relayBox.addView(MaterialButton(this).apply {
                    text = if (mine) "Stop using this phone to send" else "Use this phone to send WhatsApp"
                    setOnClickListener {
                        val action = if (mine) mapOf("action" to "configure", "removeThisPhone" to true)
                                     else mapOf("action" to "configure", "addThisPhone" to true)
                        Thread {
                            val r = cfg.adminCall(action, "whatsappRelay")
                            cfg.sync()
                            runOnUiThread {
                                Toast.makeText(this@AdminActivity,
                                    if (r?.optBoolean("success") == true) "Saved" else "Failed: " + r?.optString("error").orEmpty(),
                                    Toast.LENGTH_LONG).show()
                                loadRelay()
                            }
                        }.start()
                    }
                })
                relayBox.addView(toggle("Sending running (off = paused)", c.optBoolean("enabled")) { on, revert ->
                    sendRelay(mapOf("action" to "configure", "enabled" to on), revert)
                })
                if (mine) relayBox.addView(TextView(this).apply {
                    textSize = 12f; setPadding(0, dp(6), 0, 0)
                    text = "Last: " + RelayWorker.lastNote(this@AdminActivity).ifBlank { "nothing yet" }
                })

                // One tap to move every business's WhatsApp to the sending phones, or back.
                relayBox.addView(TextView(this).apply {
                    textSize = 12f; setPadding(0, dp(10), 0, dp(2))
                    text = "All businesses (WhatsApp only — SMS stays on each business's own number):"
                })
                relayBox.addView(MaterialButton(this).apply {
                    text = "Send ALL businesses' WhatsApp from these phones"
                    setOnClickListener { setAll(true) }
                })
                relayBox.addView(MaterialButton(this).apply {
                    text = "Let each business use its own WhatsApp again"
                    setOnClickListener { setAll(false) }
                })
            }
        }.start()
    }

    private fun setAll(enabled: Boolean) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (enabled) "Move all businesses to the sending phones?" else "Give WhatsApp back to each business?")
            .setMessage("This changes the WhatsApp setting of every business at once. SMS is not affected.")
            .setPositiveButton("Yes") { _, _ ->
                Thread {
                    val r = cfg.adminCall(mapOf("action" to "setRelayAll", "enabled" to enabled), "adminDevices")
                    runOnUiThread {
                        Toast.makeText(this,
                            if (r?.optBoolean("success") == true) "Done — ${r.optInt("changed")} business(es) changed" else "Failed: " + r?.optString("error").orEmpty(),
                            Toast.LENGTH_LONG).show()
                        load()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendRelay(values: Map<String, Any>, revert: () -> Unit) {
        Thread {
            val res = cfg.adminCall(values, "whatsappRelay")
            val ok = res != null && res.optBoolean("success", false)
            runOnUiThread {
                if (ok) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                else {
                    revert()
                    Toast.makeText(this, "Not saved: " + (res?.optString("error").orEmpty().ifBlank { "Could not reach the server." }), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
        box.addView(toggle("WhatsApp via admin relay phone", d.optBoolean("whatsappViaRelay", false)) { on, revert ->
            send(mapOf("action" to "setChannel", "targetDeviceId" to id, "channel" to "relay", "enabled" to on), revert)
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
