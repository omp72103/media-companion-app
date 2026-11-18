package com.media.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executors

/**
 * Foreground service that watches the Android CallLog for new incoming calls.
 * The moment a missed/rejected call lands, it hands it to SmsEngine which
 * evaluates the rules, sends the SMS via the phone's own SIM, and reports to
 * the dashboard. Fully automatic after install — no user interaction.
 */
class CallMonitorService : Service() {

    companion object {
        /** Read by the setup screen's status panel to show if monitoring is live. */
        @Volatile var isRunning = false
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var observer: ContentObserver? = null
    private lateinit var cfg: Config

    // ── Ring tracking ──
    // Some phones (Realme/Oppo/ColorOS) write their own call-log "type" codes,
    // so an OUTGOING call could be read as missed/rejected and get a reply.
    // We record every moment the phone actually RINGS: a call-log entry with
    // no ring around its start time was not an incoming call → outgoing.
    private var telephonyCallback: Any? = null
    private var phoneListener: PhoneStateListener? = null

    private fun onCallState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_RINGING) cfg.recordRing(System.currentTimeMillis())
    }

    private fun startRingTracking() {
        if (telephonyCallback != null || phoneListener != null) return
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCallState(state)
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                telephonyCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
                }
                @Suppress("DEPRECATION") tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneListener = l
            }
            cfg.ringTrackingSince = System.currentTimeMillis()
        }
    }

    private fun stopRingTracking() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) (telephonyCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            @Suppress("DEPRECATION") phoneListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        telephonyCallback = null; phoneListener = null
    }

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        createChannel()
        startForeground(1, buildNotification("Monitoring missed calls…"))
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the "already handled" marker at now on first start, so the
        // very first call after install gets a reply instead of being used
        // only to initialise the marker.
        if (cfg.lastCallTs == 0L) cfg.lastCallTs = System.currentTimeMillis()
        executor.execute { cfg.sync() }
        startRingTracking()
        registerObserver()
        return START_STICKY
    }

    private fun registerObserver() {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                executor.execute { processNewCalls() }
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer!!)
    }

    private fun processNewCalls() {
        var since = cfg.lastCallTs
        // First-ever run: don't replay the entire call history — start from now.
        if (since == 0L) {
            cfg.lastCallTs = System.currentTimeMillis()
            return
        }

        val proj = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
            CallLog.Calls.CACHED_NAME
        )
        val cur = contentResolver.query(
            CallLog.Calls.CONTENT_URI, proj,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            "${CallLog.Calls.DATE} ASC"
        ) ?: return

        var maxTs = since
        cur.use {
            while (it.moveToNext()) {
                val rawNumber = it.getString(0) ?: continue
                val number = rawNumber.replace(Regex("\\D"), "").takeLast(10)
                val type = it.getInt(1)
                val duration = it.getLong(2)
                val date = it.getLong(3)
                if (date > maxTs) maxTs = date
                if (number.length < 10) continue

                // Saved in the phone's contacts? (call log caches the contact name)
                val savedName = it.getString(4)?.trim().orEmpty()

                var callType = when (type) {
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    5 /* REJECTED_TYPE */ -> "rejected"
                    6 /* BLOCKED_TYPE */ -> "blocked"
                    CallLog.Calls.INCOMING_TYPE -> if (duration == 0L) "missed" else "answered"
                    // Unknown OEM code: decide by whether the phone rang (below).
                    else -> "unknown"
                }
                // Never reply to a call the phone didn't ring for — that was
                // an outgoing call, whatever type code the phone wrote.
                if (callType != "outgoing" && callType != "blocked" &&
                    cfg.ringTrackingWorks() &&
                    cfg.ringTrackingSince in 1 until date - 2000 && !cfg.rangNear(date)) {
                    callType = "outgoing"
                }
                if (callType == "unknown") callType = if (duration == 0L) "missed" else "answered"
                if (callType == "blocked") continue
                SmsEngine.handle(this, SmsEngine.Call(number, callType, date, duration, savedName.isNotEmpty()))
            }
        }
        cfg.lastCallTs = maxTs
    }

    override fun onDestroy() {
        isRunning = false
        stopRingTracking()
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel("media_call", "Media Companion", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val b = Notification.Builder(this, "media_call")
            .setContentTitle("Media Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) b.build()
        else @Suppress("DEPRECATION") b.notification
    }
}