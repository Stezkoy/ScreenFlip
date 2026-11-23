package com.screenflip.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.screenflip.app.MainActivity
import com.screenflip.app.R
import com.screenflip.app.ScreenFlipApp
import com.screenflip.app.swap.PServerSwapEngine
import com.screenflip.app.swap.SwapResult
import com.screenflip.app.swap.SwapMode
import com.screenflip.app.trigger.TccButtonStateMachine
import java.util.concurrent.Executors

/**
 * Foreground service that owns:
 * 1. the swap execution (notification action + QS tile entry point);
 * 2. the physical AYN button double-tap trigger (delegated by
 *    [com.screenflip.app.receiver.TccKeyReceiver]).
 */
class FlipService : Service() {

    companion object {
        private const val TAG = "FlipService"

        const val ACTION_START = "com.screenflip.app.START"
        const val ACTION_SWAP = "com.screenflip.app.SWAP"
        const val ACTION_TCC_KEY = "com.screenflip.app.TCC_KEY"

        /**
         * Optional [ACTION_SWAP] extra: the exact mode to run, independent of
         * the configured default (set by the notification actions).
         */
        const val EXTRA_SWAP_MODE = "swap_mode"
        const val EXTRA_KEY_ACTION_DOWN = "key_action_down"
        const val EXTRA_IS_LONG_PRESS = "isLongPress"

        internal const val TCC_BUTTON_ACTION = "action.tcc.button.key.event"

        private const val CHANNEL_ID = "screenflip"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: FlipService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val swapExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "screenflip-swap").apply { isDaemon = true }
    }
    private val tccStateMachine = TccButtonStateMachine()
    @Volatile
    private var swapInProgress = false
    @Volatile
    private var lastSwapMessage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        registerTccReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        when (intent?.action) {
            ACTION_SWAP -> {
                // The notification actions pass the exact mode; the QS tile
                // and the in-app button use the configured default.
                val mode = intent.getStringExtra(EXTRA_SWAP_MODE)?.let { SwapMode.fromKey(it) }
                    ?: ScreenFlipApp.mainSwapMode()
                performSwap(mode)
            }
            ACTION_TCC_KEY -> onTccButtonEvent(
                keyActionDown = intent.getBooleanExtra(EXTRA_KEY_ACTION_DOWN, false),
                isLongPress = intent.getBooleanExtra(EXTRA_IS_LONG_PRESS, false)
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        swapExecutor.shutdownNow()
        try {
            unregisterReceiver(tccReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    /**
     * Dynamically registered receiver for the AYN button broadcast.
     *
     * The manifest receiver only wakes the service (via ACTION_TCC_KEY) when
     * the process is asleep; while the service is alive this dynamic
     * receiver is the single source of truth, which makes every press /
     * release event reliable.
     */
    private val tccReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TCC_BUTTON_ACTION) return
            val down = intent.getBooleanExtra("key_action_down", false)
            val long = intent.getBooleanExtra("key_isLongPress", false)
            onTccButtonEvent(down, long)
        }
    }

    private fun registerTccReceiver() {
        val filter = IntentFilter(TCC_BUTTON_ACTION)
        if (Build.VERSION.SDK_INT >= 34) {
            registerReceiver(
                tccReceiver, filter, android.Manifest.permission.DUMP,
                null, Context.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(tccReceiver, filter, android.Manifest.permission.DUMP, null)
        }
    }

    /**
     * Entry point for the TCC (AYN) button broadcast. The manifest receiver
     * delegates here; the service is the single processor of the events.
     */
    fun onTccButtonEvent(keyActionDown: Boolean, isLongPress: Boolean) {
        Log.i(TAG, "TCC event: down=$keyActionDown long=$isLongPress")
        if (!ScreenFlipApp.prefs.getBoolean(
                ScreenFlipApp.KEY_AYN_DOUBLE_TAP_ENABLED, false
            )
        ) {
            return
        }
        if (tccStateMachine.onButtonEvent(keyActionDown, isLongPress)) {
            performSwap(ScreenFlipApp.aynSwapMode())
        }
    }

    /** Returns the human-readable outcome of the most recent swap. */
    fun getLastSwapMessage(): String? = lastSwapMessage

    /**
     * Performs the screen swap on a background thread and reports the
     * outcome with a toast on the main thread.
     */
    fun performSwap(mode: SwapMode = ScreenFlipApp.mainSwapMode()) {
        if (swapInProgress) return
        swapInProgress = true
        val context = applicationContext
        swapExecutor.execute {
            val result = PServerSwapEngine.swap(context, mode)
            mainHandler.post {
                swapInProgress = false
                if (this@FlipService != null) {
                    val message = when (result) {
                        is SwapResult.Swapped ->
                            context.getString(R.string.toast_swapped)
                        is SwapResult.Partial ->
                            context.getString(R.string.toast_swapped_partial)
                        is SwapResult.Moved ->
                            context.getString(
                                if (result.toBottom) R.string.toast_moved_down else R.string.toast_moved_up
                            )
                        is SwapResult.NoTasks ->
                            context.getString(
                                when (mode) {
                                    SwapMode.SWAP -> R.string.toast_no_tasks
                                    SwapMode.TOP_TO_BOTTOM -> R.string.toast_no_top_app
                                    SwapMode.BOTTOM_TO_TOP -> R.string.toast_no_bottom_app
                                }
                            )
                        is SwapResult.Failed ->
                            context.getString(R.string.toast_failed, result.reason)
                    }
                    lastSwapMessage = message
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notification_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_swap)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            // Three independent actions: each runs its exact mode, regardless
            // of the configured default (the QS tile tap uses the default).
            .addAction(0, getString(R.string.mode_swap), swapModeAction(1, SwapMode.SWAP.key))
            .addAction(1, getString(R.string.mode_top_to_bottom), swapModeAction(2, SwapMode.TOP_TO_BOTTOM.key))
            .addAction(2, getString(R.string.mode_bottom_to_top), swapModeAction(3, SwapMode.BOTTOM_TO_TOP.key))
            .build()
    }

    private fun swapModeAction(requestCode: Int, modeKey: String): PendingIntent {
        val intent = Intent(this, FlipService::class.java)
            .setAction(ACTION_SWAP)
            .putExtra(EXTRA_SWAP_MODE, modeKey)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
