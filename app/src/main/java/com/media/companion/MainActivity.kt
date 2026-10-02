package com.media.companion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var cfg: Config

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* runtime grant state is re-checked by the service when it queries CallLog */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cfg = Config(this)

        val baseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val deviceId = findViewById<EditText>(R.id.etDeviceId)
        val token = findViewById<EditText>(R.id.etToken)
        val status = findViewById<TextView>(R.id.tvStatus)

        baseUrl.setText(cfg.baseUrl())
        deviceId.setText(cfg.deviceId())
        token.setText(cfg.token())

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            cfg.save(baseUrl.text.toString().trim(), deviceId.text.toString().trim(), token.text.toString().trim())
            requestPerms()
            runCatching { startMonitor() }
            status.text = "Companion running. Missed calls will be auto-replied."
            Toast.makeText(this, "Saved & started", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            // Always persist whatever is currently typed before syncing, so
            // "Sync Now" never runs against stale/empty saved values just
            // because "Save & Start" wasn't tapped first.
            cfg.save(baseUrl.text.toString().trim(), deviceId.text.toString().trim(), token.text.toString().trim())
            status.text = "Syncing…"
            Thread {
                val ok = cfg.sync()
                runOnUiThread {
                    status.text = if (ok) "Synced with server. Companion ready." else "Sync failed — check Device ID & Token."
                    Toast.makeText(this, if (ok) "Synced ✓" else "Sync failed", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CallMonitorService::class.java))
            status.text = "Stopped."
        }

        val testNumber = findViewById<EditText>(R.id.etTestNumber)
        findViewById<Button>(R.id.btnTestSms).setOnClickListener {
            val number = testNumber.text.toString().trim()
            status.text = "Sending test SMS…"
            Thread {
                val (ok, msg) = SmsEngine.sendTest(this, number)
                runOnUiThread {
                    status.text = msg
                    Toast.makeText(this, if (ok) "Test SMS sent ✓" else "Test SMS failed", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        requestPerms()
        setupSimPicker()
        if (cfg.isConfigured()) {
            // Defensive: never let a foreground-service start failure crash the
            // activity itself, or every future app open would crash-loop and
            // lock the user out of the settings screen entirely.
            runCatching { startMonitor() }
        }
    }

    private fun requestPerms() {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permsLauncher.launch(perms.toTypedArray())
    }

    private fun startMonitor() {
        val intent = Intent(this, CallMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ContextCompat.startForegroundService(this, intent)
        else
            startService(intent)
    }

    /**
     * Lists active SIMs (if any) so the user can pick which one the app
     * always sends from, instead of trusting the phone's own default SMS
     * SIM — which can be set to a SIM with no balance/signal while the
     * registered business number is actually on the other SIM.
     */
    private fun setupSimPicker() {
        val spinner = findViewById<Spinner>(R.id.spinnerSim)
        val subs = runCatching {
            getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList ?: emptyList()
        }.getOrDefault(emptyList())

        val labels = mutableListOf("Use phone default")
        val ids = mutableListOf(-1)
        subs?.forEach { info ->
            labels.add("SIM ${info.simSlotIndex + 1} — ${info.carrierName}")
            ids.add(info.subscriptionId)
        }

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val savedIndex = ids.indexOf(cfg.simSubscriptionId()).let { if (it < 0) 0 else it }
        spinner.setSelection(savedIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                cfg.setSimSubscriptionId(ids[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
