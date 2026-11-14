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
import android.widget.RadioGroup
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
    private lateinit var swEnabled: MaterialSwitch
    private lateinit var swMissed: MaterialSwitch
    private lateinit var swRejected: MaterialSwitch
    private lateinit var swAnswered: MaterialSwitch
    private lateinit var swOutgoing: MaterialSwitch
    private lateinit var swFollowup: MaterialSwitch
    private lateinit var rgRecipient: RadioGroup
    private lateinit var etBusinessName: EditText
    private lateinit var etCallback: EditText
    private lateinit var etMessage: EditText
    private lateinit var etFollowupHours: EditText
    private lateinit var etFollowupMessage: EditText
    private lateinit var swWhatsapp: MaterialSwitch
    private lateinit var swWhatsappBusiness: MaterialSwitch
    private lateinit var etWhatsappMessage: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var etToken: EditText
    private lateinit var status: TextView
    private lateinit var tvDiag: TextView
    private lateinit var tvDiagHint: TextView

    private fun setupScreen() {
        swEnabled = findViewById(R.id.swEnabled)
        swMissed = findViewById(R.id.swMissed)
        swRejected = findViewById(R.id.swRejected)
        swAnswered = findViewById(R.id.swAnswered)
        swOutgoing = findViewById(R.id.swOutgoing)
        swFollowup = findViewById(R.id.swFollowup)
        rgRecipient = findViewById(R.id.rgRecipient)
        etBusinessName = findViewById(R.id.etBusinessName)
        etCallback = findViewById(R.id.etCallback)
        etMessage = findViewById(R.id.etMessage)
        etFollowupHours = findViewById(R.id.etFollowupHours)
        etFollowupMessage = findViewById(R.id.etFollowupMessage)
        swWhatsapp = findViewById(R.id.swWhatsapp)
        swWhatsappBusiness = findViewById(R.id.swWhatsappBusiness)
        etWhatsappMessage = findViewById(R.id.etWhatsappMessage)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etDeviceId = findViewById(R.id.etDeviceId)
        etToken = findViewById(R.id.etToken)
        status = findViewById(R.id.tvStatus)
        tvDiag = findViewById(R.id.tvDiag)
        tvDiagHint = findViewById(R.id.tvDiagHint)

        // ── Prefill from saved values (defaults are sensible on first run) ──
        swEnabled.isChecked = cfg.localEnabled()
        swMissed.isChecked = cfg.localTrigMissed()
        swRejected.isChecked = cfg.localTrigRejected()
        swAnswered.isChecked = cfg.localTrigAnswered()
        swOutgoing.isChecked = cfg.localTrigOutgoing()
        when (cfg.localRecipientMode()) {
            1 -> rgRecipient.check(R.id.rbUnknown)
            2 -> rgRecipient.check(R.id.rbContacts)
            else -> rgRecipient.check(R.id.rbEveryone)
        }
        etBusinessName.setText(cfg.localBusinessName())
        etCallback.setText(cfg.localCallback())
        etMessage.setText(cfg.localMessage())
        swFollowup.isChecked = cfg.localFollowupEnabled()
        etFollowupHours.setText(cfg.localFollowupHours().toString())
        etFollowupMessage.setText(cfg.localFollowupMessage())
        swWhatsapp.isChecked = cfg.localWhatsappEnabled()
        swWhatsappBusiness.isChecked = cfg.localWhatsappBusiness()
        etWhatsappMessage.setText(cfg.localWhatsappMessage())
        etBaseUrl.setText(cfg.baseUrl())
        etDeviceId.setText(cfg.deviceId())
        etToken.setText(cfg.token())

        if (cfg.isConfigured() || cfg.localRulesSet()) {
            status.text = "Running — replying to calls automatically."
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            persistAll()
            requestPerms()
            runCatching { startMonitor() }
            status.text = "Saved. Replying to calls automatically."
            Toast.makeText(this, "Saved & started", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnSync).setOnClickListener {
            // Persist whatever is on screen first, so a sync never discards
            // on-device edits the user just made.
            persistAll()
            status.text = "Syncing…"
            Thread {
                val ok = cfg.sync()
                runOnUiThread {
                    status.text = if (ok) {
                        "Synced with dashboard. Rules saved on-device still apply."
                    } else {
                        "Sync failed — check App URL, Device ID & Token."
                    }
                    Toast.makeText(this, if (ok) "Synced ✓" else "Sync failed", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CallMonitorService::class.java))
            status.text = "Stopped. No automatic replies will be sent."
        }

        findViewById<MaterialButton>(R.id.btnWaAccess).setOnClickListener {
            persistAll()
            WhatsAppSender.openAccessibilitySettings(this)
            Toast.makeText(this, "Enable \"Missed Call Auto Reply\" in the list", Toast.LENGTH_LONG).show()
        }

        findViewById<MaterialButton>(R.id.btnWaOverlay).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
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

        val testNumber = findViewById<EditText>(R.id.etTestNumber)
        findViewById<MaterialButton>(R.id.btnTestSms).setOnClickListener {
            persistAll() // so the test uses the message currently on screen
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
        if (cfg.isConfigured() || cfg.localRulesSet()) {
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
        val recipientMode = when (rgRecipient.checkedRadioButtonId) {
            R.id.rbUnknown -> 1
            R.id.rbContacts -> 2
            else -> 0
        }
        val followupHours = etFollowupHours.text.toString().trim().toIntOrNull() ?: 12
        cfg.saveRules(
            enabled = swEnabled.isChecked,
            missed = swMissed.isChecked,
            rejected = swRejected.isChecked,
            answered = swAnswered.isChecked,
            outgoing = swOutgoing.isChecked,
            recipientMode = recipientMode,
            message = etMessage.text.toString().trim(),
            businessName = etBusinessName.text.toString().trim(),
            callback = etCallback.text.toString().trim(),
            followupEnabled = swFollowup.isChecked,
            followupHours = followupHours,
            followupMessage = etFollowupMessage.text.toString().trim()
        )
        cfg.saveWhatsapp(
            enabled = swWhatsapp.isChecked,
            useBusiness = swWhatsappBusiness.isChecked,
            message = etWhatsappMessage.text.toString().trim()
        )
    }

    override fun onResume() {
        super.onResume()
        if (::tvDiag.isInitialized) refreshStatus()
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
        if (cfg.localWhatsappEnabled()) {
            val waAcc = WhatsAppSender.isAccessibilityEnabled(this)
            val overlay = Settings.canDrawOverlays(this)
            sb.append("\n${mark(waAcc)}  WhatsApp auto-send (Accessibility)")
            sb.append("\n${mark(overlay)}  Display over other apps")
        }
        tvDiag.text = sb.toString()
        tvDiagHint.visibility = if (!sms || !callLog || !phone) View.VISIBLE else View.GONE
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
