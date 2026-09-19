package com.media.companion

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
            startMonitor()
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

        requestPerms()
        if (cfg.isConfigured()) startMonitor()
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
}