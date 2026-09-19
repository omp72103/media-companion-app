package com.media.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import java.util.concurrent.Executors

/**
 * Foreground service that watches the Android CallLog for new incoming calls.
 * The moment a missed/rejected call lands, it hands it to SmsEngine which
 * evaluates the rules, sends the SMS via the phone's own SIM, and reports to
 * the dashboard. Fully automatic after install — no user interaction.
 */
class CallMonitorService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private var observer: ContentObserver? = null
    private lateinit var cfg: Config

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        createChannel()
        startForeground(1, buildNotification("Monitoring missed calls…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        executor.execute { cfg.sync() }
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
            CallLog.Calls.DATE
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

                val callType = when (type) {
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    5 /* REJECTED_TYPE */ -> "rejected"
                    CallLog.Calls.INCOMING_TYPE -> if (duration == 0L) "missed" else "answered"
                    else -> if (duration == 0L) "missed" else "answered"
                }
                SmsEngine.handle(this, SmsEngine.Call(number, callType, date, duration))
            }
        }
        cfg.lastCallTs = maxTs
    }

    override fun onDestroy() {
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