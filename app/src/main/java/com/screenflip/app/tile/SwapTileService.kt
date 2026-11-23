package com.screenflip.app.tile

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.screenflip.app.R
import com.screenflip.app.ScreenFlipApp
import com.screenflip.app.service.FlipService
import com.screenflip.app.swap.SwapMode

/**
 * Quick Settings tile for ScreenFlip.
 *
 * - A tap performs the screen swap with the currently selected default mode
 *   ([FlipService.ACTION_SWAP]).
 * - The tile shows the app name as the label and the default swap mode as
 *   the status line below it (`label` + status under the label, no extra
 *   affordances).
 *
 * Note: the TileService API has NO long-press callback - a long-press always
 * opens the system "about app" page drawn by SystemUI. From that page the
 * user taps the app icon to open ScreenFlip and change the default mode.
 */
class SwapTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val swapIntent = Intent(this, FlipService::class.java).setAction(FlipService.ACTION_SWAP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(swapIntent)
        } else {
            startService(swapIntent)
        }
        // A click on a dead tile restarts the process (FlipService comes up
        // in Application.onCreate) - refresh the highlighted state.
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    /**
     * Tile state: app name as the label, the default swap mode as the
     * status line under it (the TOGGLEABLE_TILE manifest flag keeps the OEM
     * chevron away even with a non-empty subtitle).
     *
     * The tile is highlighted while the swap service is running and dark
     * when the app was killed: both services share the process, so a live
     * tile process means the service is up. When the process dies, SystemUI
     * resets the tile to the inactive look on its own.
     */
    private fun updateTileState() {
        val modeLabel = modeLabel(ScreenFlipApp.mainSwapMode())
        getQsTile()?.let { tile ->
            tile.label = getString(R.string.app_name)
            tile.state =
                if (FlipService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = modeLabel
            tile.contentDescription = ""
            tile.updateTile()
        }
        // The tile refresher asks for listening state statically; the call
        // is wrapped because it throws on some OEM/Android 13+ builds.
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, SwapTileService::class.java)
            )
        }
    }

    private fun modeLabel(mode: SwapMode): String = when (mode) {
        SwapMode.SWAP -> getString(R.string.mode_swap)
        SwapMode.TOP_TO_BOTTOM -> getString(R.string.mode_top_to_bottom)
        SwapMode.BOTTOM_TO_TOP -> getString(R.string.mode_bottom_to_top)
    }
}
