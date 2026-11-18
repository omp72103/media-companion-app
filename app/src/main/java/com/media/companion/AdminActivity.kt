package com.media.companion

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Admin Control Centre — shown only to an admin phone (the server reports
 * isAdmin on sync). Lists every registered device and lets the admin turn
 * each one's auto-reply on or off remotely, via the adminDevices function.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var container: LinearLayout
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        cfg = Config(this)
        container = findViewById(R.id.llDevices)
        statusView = findViewById(R.id.tvAdminStatus)
        findViewById<MaterialButton>(R.id.btnAdminRefresh).setOnClickListener { loadDevices() }
        loadDevices()
    }

    private fun loadDevices() {
        statusView.text = "Loading devices…"
        container.removeAllViews()
        Thread {
            val devices = AdminApi.list(cfg)
            runOnUiThread {
                if (devices == null) {
                    statusView.text = "Could not load devices. Check this phone is paired and online, then Refresh."
                    return@runOnUiThread
                }
                if (devices.isEmpty()) {
                    statusView.text = "No devices registered yet."
                    return@runOnUiThread
                }
                val on = devices.count { it.enabled && it.status == "active" }
                statusView.text = "${devices.size} devices · $on on · ${devices.size - on} off"
                devices.forEach { addRow(it) }
            }
        }.start()
    }

    private fun addRow(d: AdminApi.Device) {
        val row = layoutInflater.inflate(R.layout.admin_device_row, container, false)
        val name = row.findViewById<TextView>(R.id.tvRowName)
        val sub = row.findViewById<TextView>(R.id.tvRowSub)
        val stats = row.findViewById<TextView>(R.id.tvRowStats)
        val sw = row.findViewById<MaterialSwitch>(R.id.swRowEnabled)

        name.text = if (d.name.isNotBlank()) d.name else "(unnamed device)"
        val bits = mutableListOf<String>()
        if (d.phone.isNotBlank()) bits.add("+91 ${d.phone}")
        if (d.business.isNotBlank()) bits.add(d.business)
        sub.text = bits.joinToString(" · ")
        val remotelyOn = d.enabled && d.status == "active"
        stats.text = "${if (remotelyOn) "ON" else "OFF"} · ${d.sms} SMS · ${d.calls} calls"

        // Set state without firing the listener.
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = remotelyOn
        sw.setOnCheckedChangeListener { _, checked ->
            sw.isEnabled = false
            Thread {
                val ok = AdminApi.setEnabled(cfg, d.id, checked)
                runOnUiThread {
                    sw.isEnabled = true
                    if (ok) {
                        stats.text = "${if (checked) "ON" else "OFF"} · ${d.sms} SMS · ${d.calls} calls"
                        Toast.makeText(
                            this,
                            "${d.name}: auto-reply ${if (checked) "ON" else "OFF"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        // Revert the toggle on failure.
                        sw.setOnCheckedChangeListener(null)
                        sw.isChecked = !checked
                        sw.setOnCheckedChangeListener { _, c -> toggle(d, c, sw, stats) }
                        Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        container.addView(row)
    }

    // Used only when re-attaching the listener after a failed toggle.
    private fun toggle(d: AdminApi.Device, checked: Boolean, sw: MaterialSwitch, stats: TextView) {
        sw.isEnabled = false
        Thread {
            val ok = AdminApi.setEnabled(cfg, d.id, checked)
            runOnUiThread {
                sw.isEnabled = true
                if (ok) {
                    stats.text = "${if (checked) "ON" else "OFF"} · ${d.sms} SMS · ${d.calls} calls"
                } else {
                    sw.setOnCheckedChangeListener(null)
                    sw.isChecked = !checked
                    sw.setOnCheckedChangeListener { _, c -> toggle(d, c, sw, stats) }
                    Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
