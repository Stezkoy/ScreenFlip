package com.screenflip.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screenflip.app.ScreenFlipApp
import com.screenflip.app.service.FlipService

/**
 * Restarts the foreground service after a device reboot so that the QS
 * tile, the notification action and the AYN button trigger keep working
 * without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, FlipService::class.java)
                .setAction(FlipService.ACTION_START)
            context.startForegroundService(serviceIntent)
        }
    }
}
