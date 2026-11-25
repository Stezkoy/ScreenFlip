package com.screenflip.app.tile

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.screenflip.app.MainActivity
import com.screenflip.app.R
import com.screenflip.app.ScreenFlipApp
import com.screenflip.app.swap.SwapMode
import com.screenflip.app.theme.ScreenFlipTheme

/**
 * Translucent popup opened from the QS tile: the user picks the default
 * swap mode (persisted as the main action) or opens the app itself.
 *
 * The "open app" entry exists because the QS tile has no long-press
 * callback: the system "about app" dialog is drawn by SystemUI and cannot
 * be replaced.
 */
class ModeSelectionActivity : ComponentActivity() {

    private var darkTheme = false

    override fun onCreate(savedInstanceState: Bundle?) {
        darkTheme = resolveDarkTheme()
        setTheme(if (darkTheme) R.style.Theme_ScreenFlip_Dialog_Dark else R.style.Theme_ScreenFlip_Dialog_Light)
        super.onCreate(savedInstanceState)
        setContent {
            ScreenFlipTheme(darkTheme = darkTheme) {
                ModeSelectionContent(
                    onDismiss = { finish() },
                    onModeSelected = { mode ->
                        ScreenFlipApp.prefs.edit()
                            .putString(ScreenFlipApp.KEY_SWAP_MODE_MAIN, mode.key)
                            .apply()
                        Toast.makeText(
                            this,
                            getString(R.string.tile_mode_saved, getString(modeLabelRes(mode))),
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    private fun resolveDarkTheme(): Boolean {
        val mode = ScreenFlipApp.prefs.getString(ScreenFlipApp.KEY_THEME_MODE, ScreenFlipApp.THEME_SYSTEM)
        return when (mode) {
            ScreenFlipApp.THEME_DARK -> true
            ScreenFlipApp.THEME_LIGHT -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }
}

private fun modeLabelRes(mode: SwapMode): Int = when (mode) {
    SwapMode.SWAP -> R.string.mode_swap
    SwapMode.TOP_TO_BOTTOM -> R.string.mode_top_to_bottom
    SwapMode.BOTTOM_TO_TOP -> R.string.mode_bottom_to_top
}

@androidx.compose.runtime.Composable
private fun ModeSelectionContent(
    onDismiss: () -> Unit,
    onModeSelected: (SwapMode) -> Unit,
    onOpenApp: () -> Unit
) {
    var selected by remember { mutableStateOf(ScreenFlipApp.mainSwapMode()) }
    // Tapping the dimmed area outside the card dismisses the popup.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .width(320.dp)
                .clickable(enabled = true) { /* swallow taps on the card */ }
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.tile_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
                FilterChip(
                    selected = selected == SwapMode.SWAP,
                    onClick = { selected = SwapMode.SWAP; onModeSelected(SwapMode.SWAP) },
                    label = { Text(stringResource(R.string.mode_swap)) },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = selected == SwapMode.TOP_TO_BOTTOM,
                    onClick = { selected = SwapMode.TOP_TO_BOTTOM; onModeSelected(SwapMode.TOP_TO_BOTTOM) },
                    label = { Text(stringResource(R.string.mode_top_to_bottom)) },
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = selected == SwapMode.BOTTOM_TO_TOP,
                    onClick = { selected = SwapMode.BOTTOM_TO_TOP; onModeSelected(SwapMode.BOTTOM_TO_TOP) },
                    label = { Text(stringResource(R.string.mode_bottom_to_top)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tile_open_app))
                }
            }
        }
    }
}