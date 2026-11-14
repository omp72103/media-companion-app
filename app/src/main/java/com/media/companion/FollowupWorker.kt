package com.media.companion

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Fires some hours after an auto-reply was sent (see SmsEngine.scheduleFollowup).
 * It hands straight back to SmsEngine, which re-checks the call log and only
 * sends the follow-up SMS if the caller still hasn't reconnected.
 *
 * WorkManager persists this across reboots and Doze on its own, so no boot
 * receiver or exact-alarm permission is needed.
 */
class FollowupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val number = inputData.getString("number") ?: return Result.success()
        val baseTime = inputData.getLong("baseTime", 0L)
        runCatching { SmsEngine.sendFollowupNow(applicationContext, number, baseTime) }
        return Result.success()
    }
}
