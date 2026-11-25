package com.screenflip.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screenflip.app.service.FlipService

/**
 * Restarts the foreground service after a device reboot or after the app
 * itself was updated, so that the QS tile, the notification action and the
 * AYN button trigger keep working without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val serviceIntent = Intent(context, FlipService::class.java)
                .setAction(FlipService.ACTION_START)
            runCatching { context.startForegroundService(serviceIntent) }
                .onFailure { Log.e(TAG, "Could not start FlipService after ${intent.action}", it) }
        }
    }
}
