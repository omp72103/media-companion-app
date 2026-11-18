package com.media.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var cfg: Config

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Permission grant state is only known once this callback fires —
        // refresh the SIM list now, since reading active subscriptions
        // requires READ_PHONE_STATE and the earlier call in onCreate() may
        // have run before the user actually answered the dialog.
        setupSimPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cfg = Config(this)

        if (!cfg.consentGiven()) {
            showConsentDialog()
        } else {
            setupScreen()
        }
    }

    /**
     * Plain-language disclosure the business owner must explicitly agree to
     * BEFORE any Android permission dialog is shown and before the monitor
     * service can ever start.
     */
    private fun showConsentDialog() {
        val message = """
            Before you continue, please read this.

            This app is a missed-call auto-reply tool for THIS phone. Once you agree and set it up, it will, automatically and without asking each time:

            •  Read this phone's call log (who called, when, how long, and whether the call was missed, rejected, answered, or outgoing).
            •  Send an automatic SMS text message from this phone's own SIM in reply to the call types you choose, using the message you set.
            •  If you connect it to a dashboard, send a record of each call and SMS (caller number, call type, time, duration, and whether the SMS was sent) to that business's own dashboard server.

            Only set this up on a phone you own or are explicitly authorized to configure this way. A persistent notification stays visible the whole time it's active, and you can stop it at any time from this screen.

            By tapping "I Understand & Agree" you confirm you have the authority to enable this on this device and that you understand what it does.
        """.trimIndent()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Before you set this up")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("I Understand & Agree") { _, _ ->
                cfg.setConsentGiven(true)
                setupScreen()
            }
            .setNegativeButton("Decline & Exit") { _, _ ->
                finish()
            }
            .create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    // Views
    private lateinit var tvRules: TextView
    private val ruleSwitches = linkedMapOf<String, MaterialSwitch>()
    private lateinit var swIncomingAll: MaterialSwitch
    private var bindingRules = false
    private lateinit var tvMessagePreview: TextView
    private lateinit var swFollowup: MaterialSwitch
    private lateinit var etFollowupHours: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etToken: EditText
    private lateinit var status: TextView
    private lateinit var tvDiag: TextView
    private lateinit var tvDiagHint: TextView

    private fun setupScreen() {
        tvRules = findViewById(R.id.tvRules)
        ruleSwitches["enabled"] = findViewById(R.id.swEnabled)
        ruleSwitches["triggerAnswered"] = findViewById(R.id.swAnswered)
        ruleSwitches["triggerMissed"] = findViewById(R.id.swMissed)
        ruleSwitches["triggerRejected"] = findViewById(R.id.swRejected)
        ruleSwitches["triggerOutgoing"] = findViewById(R.id.swOutgoing)
        ruleSwitches["recipientAll"] = findViewById(R.id.swRecipAll)
        ruleSwitches["recipientUnknown"] = findViewById(R.id.swRecipUnknown)
        ruleSwitches["recipientContacts"] = findViewById(R.id.swRecipContacts)
        swIncomingAll = findViewById(R.id.swIncomingAll)
        ruleSwitches.forEach { (key, sw) ->
            sw.setOnCheckedChangeListener { _, on ->
                if (bindingRules) return@setOnCheckedChangeListener
                val sv = cfg.effectiveSettings()
                when (key) {
                    // "All numbers" = Unknown + Saved together.
                    "recipientAll" -> saveRules(mapOf("recipientAll" to on, "recipientUnknown" to on, "recipientContacts" to on))
                    "recipientUnknown" -> saveRules(mapOf("recipientUnknown" to on,
                        "recipientAll" to (on && sv.optBoolean("recipientContacts"))))
                    "recipientContacts" -> saveRules(mapOf("recipientContacts" to on,
                        "recipientAll" to (on && sv.optBoolean("recipientUnknown"))))
                    else -> saveRules(mapOf(key to on))
                }
            }
        }
        val cdButtons = mapOf(R.id.btnCd0 to 0, R.id.btnCd1 to 1, R.id.btnCd6 to 6, R.id.btnCd24 to 24)
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tgCooldown)
            .addOnButtonCheckedListener { _, id, checked ->
                if (!checked || bindingRules) return@addOnButtonCheckedListener
                cdButtons[id]?.let { saveRules(mapOf("cooldownHours" to it)) }
            }
        swIncomingAll.setOnCheckedChangeListener { _, on ->
            if (!bindingRules) saveRules(mapOf("triggerAnswered" to on, "triggerMissed" to on, "triggerRejected" to on))
        }
        tvMessagePreview = findViewById(R.id.tvMessagePreview)
        swFollowup = findViewById(R.id.swFollowup)
        etFollowupHours = findViewById(R.id.etFollowupHours)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etDeviceId = findViewById(R.id.etDeviceId)
        etToken = findViewById(R.id.etToken)
        status = findViewById(R.id.tvStatus)
        tvDiag = findViewById(R.id.tvDiag)
        tvDiagHint = findViewById(R.id.tvDiagHint)

        // ── Prefill from saved values (defaults are sensible on first run) ──
        swFollowup.isChecked = cfg.localFollowupEnabled()
        etFollowupHours.setText(cfg.localFollowupHours().toString())
        etBaseUrl.setText(cfg.baseUrl())
        etDeviceId.setText(cfg.deviceId())
        etToken.setText(cfg.token())

        if (cfg.isConfigured()) {
            status.text = "Running — replying to calls automatically."
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            persistAll()
            requestPerms()
            runCatching { startMonitor() }
            status.text = "Saved. Replying to calls automatically."
            Toast.makeText(this, "Saved & started", Toast.LENGTH_SHORT).show()
            syncAndShow()
        }

        findViewById<MaterialButton>(R.id.btnSync).setOnClickListener {
            // Persist whatever is on screen first, so a sync never discards
            // on-device edits the user just made.
            persistAll()
            syncAndShow()
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CallMonitorService::class.java))
            status.text = "Stopped. No automatic replies will be sent."
        }

        findViewById<MaterialButton>(R.id.btnRecheck).setOnClickListener { refreshStatus() }

        findViewById<MaterialButton>(R.id.btnAppSettings).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<MaterialButton>(R.id.btnAdmin).setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }

        val testNumber = findViewById<EditText>(R.id.etTestNumber)
        findViewById<MaterialButton>(R.id.btnTestSms).setOnClickListener {
            persistAll()
            val number = testNumber.text.toString().trim()
            status.text = "Sending test SMS…"
            Thread {
                val (ok, msg) = SmsEngine.sendTest(this, number)
                runOnUiThread {
                    status.text = msg
                    Toast.makeText(this, if (ok) "Test SMS sent ✓" else msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        showSynced()
        requestPerms()
        setupSimPicker()
        if (cfg.isConfigured()) {
            runCatching { startMonitor() }
        }
    }

    /** Saves both the connection fields and the on-device rules. */
    private fun persistAll() {
        cfg.save(
            etBaseUrl.text.toString().trim(),
            etDeviceId.text.toString().trim(),
            etToken.text.toString().trim()
        )
        val followupHours = etFollowupHours.text.toString().trim().toIntOrNull() ?: 12
        cfg.saveFollowup(swFollowup.isChecked, followupHours)
    }

    /** Pulls the latest rules + message from Media and shows them. */
    private fun syncAndShow() {
        status.text = "Syncing with Media…"
        Thread {
            val ok = cfg.sync()
            runOnUiThread {
                showSynced()
                status.text = if (ok) "Synced with Media ✓ Replying to calls automatically."
                else if (!cfg.isConfigured()) "Enter Device ID & Token below, then tap Save & Start."
                else "Sync failed — check App URL, Device ID & Token (using last synced copy)."
            }
        }.start()
    }

    /** Saves switch changes on the phone at once, then to Media (reverts if Media can't be reached). */
    private fun saveRules(values: Map<String, Any>) {
        values.forEach { (k, v) -> cfg.putCachedSetting(k, v) }
        showSynced()
        status.text = "Saving to Media…"
        Thread {
            val ok = cfg.pushRules(values)
            if (ok) cfg.sync()
            runOnUiThread {
                showSynced()
                status.text = if (ok) "Saved to Media ✓" else "Couldn't reach Media — saved on this phone; will retry on next Sync."
                if (!ok) Toast.makeText(this, "Not saved to Media — check internet", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** View of what Media has set for this phone (switches + summary). */
    private fun showSynced() {
        if (!::tvRules.isInitialized) return
        cfg.effectiveSettings().let { d ->
            bindingRules = true
            ruleSwitches.forEach { (key, sw) -> sw.isChecked = d.optBoolean(key) }
            swIncomingAll.isChecked = d.optBoolean("triggerAnswered") && d.optBoolean("triggerMissed") && d.optBoolean("triggerRejected")
            bindingRules = false
        }
        if (cfg.cachedSettings() == null) {
            tvRules.text = "Not synced yet — tap Sync now."
            tvMessagePreview.text = "—"
            return
        }
        val s = cfg.effectiveSettings()
        bindingRules = true
        ruleSwitches.forEach { (key, sw) -> sw.isChecked = s.optBoolean(key) }
        ruleSwitches["recipientAll"]?.isChecked =
            s.optBoolean("recipientUnknown") && s.optBoolean("recipientContacts") ||
            (s.optBoolean("recipientAll") && !s.optBoolean("recipientUnknown") && !s.optBoolean("recipientContacts"))
        val cdNow = s.optDouble("cooldownHours", 0.0)
        val cdId = when { cdNow <= 0.0 -> R.id.btnCd0; cdNow <= 1.0 -> R.id.btnCd1; cdNow <= 6.0 -> R.id.btnCd6; else -> R.id.btnCd24 }
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.tgCooldown).check(cdId)
        swIncomingAll.isChecked = s.optBoolean("triggerAnswered") && s.optBoolean("triggerMissed") && s.optBoolean("triggerRejected")
        bindingRules = false
        val cd = s.optDouble("cooldownHours", 0.0)
        tvRules.text = buildString {
            if (!s.optBoolean("enabled")) append("⚠ Auto-reply is OFF\n")
            if (!s.optBoolean("adminSmsAllowed", true)) append("⛔ SMS turned off by Media admin\n")
            append(if (cd <= 0.0) "Every call gets a reply" else "Same number: once every ${if (cd % 1.0 == 0.0) cd.toInt().toString() else cd.toString()} h")
        }
        val tpl = cfg.effectiveTemplate()
        tvMessagePreview.text = if (tpl.isBlank()) "(default message)" else tpl
    }

    override fun onResume() {
        super.onResume()
        if (::tvDiag.isInitialized) { refreshStatus(); syncAndShow() }
    }

    /** Live readout of the things that must be on for replies to actually send. */
    private fun refreshStatus() {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val sms = granted(Manifest.permission.SEND_SMS)
        val callLog = granted(Manifest.permission.READ_CALL_LOG)
        val phone = granted(Manifest.permission.READ_PHONE_STATE)
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            granted(Manifest.permission.POST_NOTIFICATIONS) else true
        val running = CallMonitorService.isRunning

        fun mark(b: Boolean) = if (b) "✔" else "✘"
        val sb = StringBuilder()
        sb.append("${mark(sms)}  SMS sending permission\n")
        sb.append("${mark(callLog)}  Call log access\n")
        sb.append("${mark(phone)}  Phone state\n")
        sb.append("${mark(notif)}  Notifications\n")
        sb.append("${mark(running)}  Auto-reply service running")
        if (cfg.isConfigured()) {
            sb.append("\n${mark(cfg.deviceRemotelyOn())}  Enabled from dashboard")
        }
        tvDiag.text = sb.toString()
        tvDiagHint.visibility = if (!sms || !callLog || !phone) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.btnAdmin).visibility = if (cfg.isAdmin()) View.VISIBLE else View.GONE
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
     * always sends from, instead of trusting the phone's own default SMS SIM.
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
