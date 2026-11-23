package com.screenflip.app

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import com.screenflip.app.service.FlipService
import com.screenflip.app.swap.SwapMode

/**
 * Application entry point for ScreenFlip.
 *
 * ScreenFlip is a standalone companion app for AYN Thor (Odin) that swaps the
 * foreground tasks between the top and the bottom display. It relies on the
 * firmware-provided "PServerBinder" vendor shell service for the actual
 * task move - no root mods, no adb, no accessibility services required.
 */
class ScreenFlipApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        prefs = this.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // Start the foreground service right away so that the TCC button
        // receiver and the QS tile have a live target.
        startFlipServiceIfNeeded()
    }

    fun startFlipServiceIfNeeded() {
        val intent = Intent(this, FlipService::class.java).setAction(FlipService.ACTION_START)
        startForegroundService(intent)
    }

    companion object {
        const val PREFS_NAME = "screenflip_prefs"
        const val KEY_AYN_DOUBLE_TAP_ENABLED = "ayn_double_tap_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SWAP_MODE_MAIN = "swap_mode_main"
        const val KEY_SWAP_MODE_AYN = "swap_mode_ayn"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        lateinit var prefs: SharedPreferences
            private set

        lateinit var appContext: android.content.Context
            private set

        /** Action used by the QS tile, the notification and the in-app button. */
        fun mainSwapMode(): SwapMode =
            SwapMode.fromKey(prefs.getString(KEY_SWAP_MODE_MAIN, null))

        /** Action used by the AYN button double-tap trigger. */
        fun aynSwapMode(): SwapMode =
            SwapMode.fromKey(prefs.getString(KEY_SWAP_MODE_AYN, null))
    }
}
