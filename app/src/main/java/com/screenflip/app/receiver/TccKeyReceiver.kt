package com.screenflip.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screenflip.app.service.FlipService

/**
 * Manifest receiver for the AYN / Dual-Screen physical button broadcast
 * (`action.tcc.button.key.event`, sent by the firmware with the DUMP
 * permission).
 *
 * While [FlipService] is running its dynamically registered receiver handles
 * the events directly, so this receiver only has one job: wake the service
 * (forwarding the first event as a start-command extra) when the process is
 * asleep.
 */
class TccKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FlipService.TCC_BUTTON_ACTION) return
        if (FlipService.instance != null) {
            // Dynamic receiver inside the service handles this event.
            return
        }
        val keyActionDown = intent.getBooleanExtra("key_action_down", false)
        val isLongPress = intent.getBooleanExtra("key_isLongPress", false) ||
            intent.getBooleanExtra("isLongPress", false)
        val startIntent = Intent(context, FlipService::class.java)
            .setAction(FlipService.ACTION_TCC_KEY)
            .putExtra(FlipService.EXTRA_KEY_ACTION_DOWN, keyActionDown)
            .putExtra(FlipService.EXTRA_IS_LONG_PRESS, isLongPress)
        context.startForegroundService(startIntent)
    }
}
