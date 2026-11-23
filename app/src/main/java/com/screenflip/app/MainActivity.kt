package com.screenflip.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.screenflip.app.BuildConfig
import com.screenflip.app.service.FlipService
import com.screenflip.app.swap.PServerClient
import com.screenflip.app.swap.SwapMode
import com.screenflip.app.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-screen companion UI: PServer diagnostics, a manual swap test
 * button and the AYN double-tap trigger toggle.
 */
class MainActivity : ComponentActivity() {

    // Initialised without touching the context: during activity
    // instantiation the base context is still null, so
    // checkSelfPermission() cannot be called here (it would NPE).
    private val notificationPermissionGranted =
        mutableStateOf(Build.VERSION.SDK_INT < 33)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPermissionGranted.value = hasNotificationPermission()
        }

    private var isThemeRecreate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPermissionGranted.value = hasNotificationPermission()
        // Rate-limited (24h) automatic check for a newer GitHub release.
        UpdateManager.init(this)
        UpdateManager.checkForUpdates(force = false)
        applyThemeFromPrefs()
        setContent {
            MainScreen(
                hasNotificationPermission = notificationPermissionGranted.value,
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onThemeChanged = {
                    // Full recreate so the window decor / status bar pick up
                    // the new theme, not only the Compose content.
                    isThemeRecreate = true
                    recreate()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up grants / revokes made outside the in-app dialog
        // (e.g. in the system Settings).
        notificationPermissionGranted.value = hasNotificationPermission()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun applyThemeFromPrefs() {
        val mode = ScreenFlipApp.prefs.getString(
            ScreenFlipApp.KEY_THEME_MODE, ScreenFlipApp.THEME_SYSTEM
        )
        val dark = when (mode) {
            ScreenFlipApp.THEME_DARK -> true
            ScreenFlipApp.THEME_LIGHT -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) R.style.Theme_ScreenFlip_Dark else R.style.Theme_ScreenFlip)
    }
}

@androidx.compose.runtime.Composable
private fun MainScreen(
    hasNotificationPermission: Boolean,
    onRequestNotifications: () -> Unit,
    onThemeChanged: () -> Unit
) {
    var pserverAvailable by remember { mutableStateOf<Boolean?>(null) }
    var aynDoubleTapEnabled by remember {
        mutableStateOf(
            ScreenFlipApp.prefs.getBoolean(ScreenFlipApp.KEY_AYN_DOUBLE_TAP_ENABLED, false)
        )
    }
    var lastSwapMessage by remember { mutableStateOf<String?>(null) }
    var themeMode by remember {
        mutableStateOf(
            ScreenFlipApp.prefs.getString(
                ScreenFlipApp.KEY_THEME_MODE, ScreenFlipApp.THEME_SYSTEM
            )
        )
    }
    var mainMode by remember { mutableStateOf(ScreenFlipApp.mainSwapMode()) }
    var aynMode by remember { mutableStateOf(ScreenFlipApp.aynSwapMode()) }

    val context = LocalContext.current
    val autoUpdateCheckEnabled by UpdateManager.autoUpdateCheckEnabled.collectAsState()
    val updateAvailable by UpdateManager.updateAvailable.collectAsState()
    val latestReleaseInfo by UpdateManager.latestReleaseInfo.collectAsState()
    val isCheckingUpdates by UpdateManager.isChecking.collectAsState()
    val updateCheckError by UpdateManager.checkError.collectAsState()

    fun openReleasePage() {
        val url = latestReleaseInfo?.htmlUrl?.takeIf { it.isNotBlank() }
            ?: UpdateManager.FALLBACK_RELEASES_URL
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun setMainMode(mode: SwapMode) {
        mainMode = mode
        ScreenFlipApp.prefs.edit()
            .putString(ScreenFlipApp.KEY_SWAP_MODE_MAIN, mode.key)
            .apply()
    }

    fun setAynMode(mode: SwapMode) {
        aynMode = mode
        ScreenFlipApp.prefs.edit()
            .putString(ScreenFlipApp.KEY_SWAP_MODE_AYN, mode.key)
            .apply()
    }

    val darkTheme = when (themeMode) {
        ScreenFlipApp.THEME_DARK -> true
        ScreenFlipApp.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }

    fun setThemeMode(mode: String) {
        themeMode = mode
        ScreenFlipApp.prefs.edit().putString(ScreenFlipApp.KEY_THEME_MODE, mode).apply()
        onThemeChanged()
    }

    fun checkPserver() {
        pserverAvailable = PServerClient.isAvailable()
        lastSwapMessage = FlipService.instance?.getLastSwapMessage()
    }

    // Check PServer availability right after entering the app so the
    // diagnostics status is visible without pressing "Check now".
    LaunchedEffect(Unit) {
        val available = withContext(Dispatchers.IO) { PServerClient.isAvailable() }
        pserverAvailable = available
        lastSwapMessage = FlipService.instance?.getLastSwapMessage()
    }

    fun swapNow() {
        val service = FlipService.instance
        if (service != null) {
            service.performSwap()
            // Refresh the last result shortly after the (async) swap finishes.
            Handler(Looper.getMainLooper()).postDelayed({
                lastSwapMessage = service.getLastSwapMessage()
            }, 1_500)
        } else {
            // The service is started by Application/BootReceiver, so this is
            // only a safety net (e.g. after the user force-stopped it).
            val context = ScreenFlipApp.appContext
            context.startForegroundService(
                Intent(context, FlipService::class.java).setAction(FlipService.ACTION_SWAP)
            )
        }
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.section_swap),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { swapNow() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_swap_now))
                }
                if (lastSwapMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = lastSwapMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_actions),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.main_action_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modeChips(
                        selected = mainMode,
                        onSelect = { setMainMode(it) }
                    )
                }
                Text(
                    text = stringResource(R.string.ayn_action_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modeChips(
                        selected = aynMode,
                        onSelect = { setAynMode(it) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_triggers),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.ayn_double_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ayn_double_tap_label),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = aynDoubleTapEnabled,
                        onCheckedChange = { checked ->
                            aynDoubleTapEnabled = checked
                            ScreenFlipApp.prefs.edit()
                                .putBoolean(ScreenFlipApp.KEY_AYN_DOUBLE_TAP_ENABLED, checked)
                                .apply()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_theme),
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = themeMode == ScreenFlipApp.THEME_SYSTEM,
                        onClick = { setThemeMode(ScreenFlipApp.THEME_SYSTEM) },
                        label = { Text(stringResource(R.string.theme_system)) }
                    )
                    FilterChip(
                        selected = themeMode == ScreenFlipApp.THEME_LIGHT,
                        onClick = { setThemeMode(ScreenFlipApp.THEME_LIGHT) },
                        label = { Text(stringResource(R.string.theme_light)) }
                    )
                    FilterChip(
                        selected = themeMode == ScreenFlipApp.THEME_DARK,
                        onClick = { setThemeMode(ScreenFlipApp.THEME_DARK) },
                        label = { Text(stringResource(R.string.theme_dark)) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.section_diagnostics),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (pserverAvailable) {
                        true -> stringResource(R.string.pserver_available)
                        false -> stringResource(R.string.pserver_unavailable)
                        null -> stringResource(R.string.pserver_unknown)
                    },
                    color = when (pserverAvailable) {
                        true -> Color(0xFF2E7D32)
                        false -> Color(0xC62828)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { checkPserver() }) {
                    Text(stringResource(R.string.button_check))
                }
            }
        }

        if (!hasNotificationPermission) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.notifications_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRequestNotifications) {
                Text(stringResource(R.string.button_grant_notifications))
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_updates),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when {
                        isCheckingUpdates -> stringResource(R.string.checking_updates)
                        updateAvailable -> stringResource(
                            R.string.update_available_tag,
                            latestReleaseInfo?.tagName ?: ""
                        )
                        updateCheckError != null -> stringResource(R.string.update_check_failed)
                        latestReleaseInfo != null -> stringResource(R.string.up_to_date)
                        else -> stringResource(
                            R.string.app_version_label,
                            BuildConfig.VERSION_NAME
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        updateAvailable -> MaterialTheme.colorScheme.primary
                        updateCheckError != null -> Color(0xC62828)
                        latestReleaseInfo != null -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.auto_update_check),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoUpdateCheckEnabled,
                        onCheckedChange = { UpdateManager.setAutoUpdateCheckEnabled(it) }
                    )
                }
                Button(
                    onClick = {
                        when {
                            updateAvailable -> openReleasePage()
                            else -> UpdateManager.checkForUpdates(force = true)
                        }
                    },
                    enabled = !isCheckingUpdates,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            isCheckingUpdates -> stringResource(R.string.checking_updates)
                            updateAvailable -> stringResource(R.string.update_now_btn)
                            else -> stringResource(R.string.check_for_updates)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Small version footer: italic, right-aligned.
        Text(
            text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )
    }
    }
}

/**
 * The three swap-mode chips. [selected] highlights the active mode, tapping
 * a chip reports it through [onSelect].
 */
@androidx.compose.runtime.Composable
private fun modeChips(selected: SwapMode, onSelect: (SwapMode) -> Unit) {
    FilterChip(
        selected = selected == SwapMode.SWAP,
        onClick = { onSelect(SwapMode.SWAP) },
        label = { Text(stringResource(R.string.mode_swap)) }
    )
    FilterChip(
        selected = selected == SwapMode.TOP_TO_BOTTOM,
        onClick = { onSelect(SwapMode.TOP_TO_BOTTOM) },
        label = { Text(stringResource(R.string.mode_top_to_bottom)) }
    )
    FilterChip(
        selected = selected == SwapMode.BOTTOM_TO_TOP,
        onClick = { onSelect(SwapMode.BOTTOM_TO_TOP) },
        label = { Text(stringResource(R.string.mode_bottom_to_top)) }
    )
}


