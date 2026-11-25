package com.screenflip.app.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Daily background update check.
 *
 * WorkManager runs it roughly every 24 hours while the device has a network
 * connection and re-schedules it across reboots. The result is delivered as
 * a notification by [UpdateManager]; the worker only decides whether the
 * check needs a retry.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        UpdateManager.init(applicationContext)
        if (!UpdateManager.autoUpdateCheckEnabled.value) {
            return Result.success()
        }
        return try {
            UpdateManager.runScheduledCheck()
            if (UpdateManager.lastCheckSucceeded()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Periodic update check failed", t)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "UpdateCheckWorker"
    }
}
