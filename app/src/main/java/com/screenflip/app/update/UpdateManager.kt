package com.screenflip.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.screenflip.app.BuildConfig
import com.screenflip.app.MainActivity
import com.screenflip.app.R
import com.screenflip.app.ScreenFlipApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Checks for new ScreenFlip releases on GitHub.
 *
 * Fetches the latest release from the GitHub API, compares version tags
 * with [SemVerComparator], posts a notification when a newer version exists
 * and exposes the result as StateFlows for the Compose UI.
 *
 * Scheduled checks run every 24 hours through WorkManager (see
 * [UpdateCheckWorker]); they survive app restarts and wait for a network.
 * Manual checks (force = true) always run.
 *
 * Rate limits: automatic checks (force = false) run at most once per 24 hours
 * and back off for 10 minutes after a failure.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    const val DEFAULT_RELEASES_API_URL =
        "https://api.github.com/repos/Stezkoy/ScreenFlip/releases/latest"
    const val FALLBACK_RELEASES_URL =
        "https://github.com/Stezkoy/ScreenFlip/releases"
    const val AUTO_UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    const val FAILURE_BACKOFF_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
    private const val MAX_RELEASE_JSON_CHARS = 256 * 1024 // 256 KiB

    private const val UPDATE_WORK_NAME = "screenflip_update_check"
    private const val UPDATE_CHANNEL_ID = "screenflip_updates"
    private const val UPDATE_NOTIFICATION_ID = 2
    private const val UPDATE_NOTIFICATION_GROUP = "screenflip_update_group"
    private const val NOTIFICATION_REQUEST_CODE = 10

    private const val PREF_AUTO_UPDATE_CHECK_ENABLED = "auto_update_check_enabled"
    private const val PREF_LATEST_RELEASE_TAG = "latest_release_tag"
    private const val PREF_LATEST_RELEASE_URL = "latest_release_url"
    private const val PREF_LATEST_RELEASE_APK_URL = "latest_release_apk_url"
    private const val PREF_LATEST_RELEASE_APK_SIZE = "latest_release_apk_size"
    private const val PREF_NOTIFIED_TAG = "notified_update_tag"
    private const val PREF_LAST_CHECK_TIME_MS = "last_check_time_ms"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Any()

    @Volatile
    private var activeCheckJob: Job? = null

    @Volatile
    private var checkGeneration: Long = 0L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var prefsLoaded = false

    @Volatile
    internal var lastCheckTimeMs: Long = 0L

    @Volatile
    internal var lastFailedCheckElapsedMs: Long = 0L

    private val _autoUpdateCheckEnabled = MutableStateFlow(true)
    val autoUpdateCheckEnabled: StateFlow<Boolean> = _autoUpdateCheckEnabled.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _latestReleaseInfo = MutableStateFlow<AppReleaseInfo?>(null)
    val latestReleaseInfo: StateFlow<AppReleaseInfo?> = _latestReleaseInfo.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _checkError = MutableStateFlow<String?>(null)
    val checkError: StateFlow<String?> = _checkError.asStateFlow()

    private fun getPrefs(): SharedPreferences? {
        if (appContext == null) return null
        return runCatching { ScreenFlipApp.prefs }.getOrNull()
    }

    internal fun isValidGithubReleaseUrl(urlStr: String): Boolean {
        if (urlStr.isBlank()) return false
        return try {
            val parsed = URL(urlStr.trim())
            val host = parsed.host?.lowercase().orEmpty()
            parsed.protocol.equals("https", ignoreCase = true) &&
                (host == "github.com" || host.endsWith(".github.com"))
        } catch (_: Throwable) {
            false
        }
    }

    fun init(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME
    ) {
        synchronized(checkLock) {
            if (appContext == null) {
                appContext = context.applicationContext ?: context
            }
            if (prefsLoaded) return

            val prefs = getPrefs() ?: return
            prefsLoaded = true
            runCatching {
                val autoEnabled = prefs.getBoolean(PREF_AUTO_UPDATE_CHECK_ENABLED, true)
                _autoUpdateCheckEnabled.value = autoEnabled
                lastCheckTimeMs = prefs.getLong(PREF_LAST_CHECK_TIME_MS, 0L)

                val savedTag = prefs.getString(PREF_LATEST_RELEASE_TAG, "") ?: ""
                val savedUrl = prefs.getString(PREF_LATEST_RELEASE_URL, "") ?: ""
                if (savedTag.isNotEmpty() && isValidGithubReleaseUrl(savedUrl)) {
                    val apkUrl = prefs.getString(PREF_LATEST_RELEASE_APK_URL, "") ?: ""
                    val info = AppReleaseInfo(
                        tagName = savedTag,
                        htmlUrl = savedUrl,
                        apkUrl = apkUrl.takeIf { isValidGithubReleaseUrl(it) },
                        apkSizeBytes = prefs.getLong(PREF_LATEST_RELEASE_APK_SIZE, 0L)
                    )
                    applyReleaseInfo(info, currentVersion)
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to load update preferences", e)
            }
        }
    }

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        _autoUpdateCheckEnabled.value = enabled
        scope.launch {
            runCatching {
                getPrefs()?.edit()
                    ?.putBoolean(PREF_AUTO_UPDATE_CHECK_ENABLED, enabled)
                    ?.apply()
            }.onFailure { e ->
                Log.e(TAG, "Failed to save autoUpdateCheckEnabled", e)
            }
        }
        if (enabled) scheduleAutoCheck() else cancelAutoCheck()
    }

    /**
     * Schedules the daily background update check. The work survives process
     * death and device reboots (WorkManager keeps its own database) and waits
     * for a network connection before running.
     */
    fun scheduleAutoCheck() {
        val context = appContext ?: return
        if (!_autoUpdateCheckEnabled.value) {
            cancelAutoCheck()
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UPDATE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }.onFailure { e ->
            Log.e(TAG, "Failed to schedule the periodic update check", e)
        }
    }

    /** Cancels the daily background update check. */
    fun cancelAutoCheck() {
        val context = appContext ?: return
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(UPDATE_WORK_NAME)
        }.onFailure { e ->
            Log.e(TAG, "Failed to cancel the periodic update check", e)
        }
    }

    fun checkForUpdates(
        force: Boolean = false,
        currentVersion: String = BuildConfig.VERSION_NAME,
        releasesApiUrl: String = DEFAULT_RELEASES_API_URL
    ) {
        if (!force) {
            if (!prefsLoaded || !_autoUpdateCheckEnabled.value) {
                Log.d(TAG, "Auto update check is disabled or uninitialized; skipping")
                return
            }
            val now = System.currentTimeMillis()
            if (lastCheckTimeMs > 0L && now >= lastCheckTimeMs && (now - lastCheckTimeMs) < AUTO_UPDATE_CHECK_INTERVAL_MS) {
                Log.d(TAG, "Auto update check already performed recently; skipping")
                return
            }
            val nowElapsed = SystemClock.elapsedRealtime()
            if (lastFailedCheckElapsedMs > 0L &&
                nowElapsed >= lastFailedCheckElapsedMs &&
                (nowElapsed - lastFailedCheckElapsedMs) < FAILURE_BACKOFF_INTERVAL_MS
            ) {
                Log.d(TAG, "Auto update check in offline failure backoff window; skipping")
                return
            }
        }

        val myGeneration: Long
        synchronized(checkLock) {
            if (_isChecking.value || activeCheckJob?.isActive == true) return
            _isChecking.value = true
            _checkError.value = null
            myGeneration = ++checkGeneration
            activeCheckJob = scope.launch {
                try {
                    val releaseInfo = fetchLatestRelease(releasesApiUrl)
                    synchronized(checkLock) {
                        if (myGeneration != checkGeneration) return@launch
                        if (releaseInfo != null) {
                            lastCheckTimeMs = System.currentTimeMillis()
                            lastFailedCheckElapsedMs = 0L
                            applyReleaseInfo(releaseInfo, currentVersion)
                            runCatching {
                                getPrefs()?.edit()
                                    ?.putString(PREF_LATEST_RELEASE_TAG, releaseInfo.tagName)
                                    ?.putString(PREF_LATEST_RELEASE_URL, releaseInfo.htmlUrl)
                                    ?.putString(PREF_LATEST_RELEASE_APK_URL, releaseInfo.apkUrl.orEmpty())
                                    ?.putLong(PREF_LATEST_RELEASE_APK_SIZE, releaseInfo.apkSizeBytes)
                                    ?.putLong(PREF_LAST_CHECK_TIME_MS, lastCheckTimeMs)
                                    ?.apply()
                            }
                            Log.i(
                                TAG,
                                "Update check complete: latest=${releaseInfo.tagName}, " +
                                    "current=$currentVersion, available=${_updateAvailable.value}, " +
                                    "apk=${releaseInfo.apkUrl != null}"
                            )
                        } else {
                            lastFailedCheckElapsedMs = SystemClock.elapsedRealtime()
                            _checkError.value = "Failed to fetch release info"
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(checkLock) {
                        if (myGeneration == checkGeneration) {
                            lastFailedCheckElapsedMs = SystemClock.elapsedRealtime()
                            Log.e(TAG, "Error checking for updates", e)
                            _checkError.value = e.message ?: "Network error"
                        }
                    }
                } finally {
                    synchronized(checkLock) {
                        if (myGeneration == checkGeneration) {
                            _isChecking.value = false
                            activeCheckJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Runs a check triggered by [UpdateCheckWorker] and waits for it to
     * finish, so WorkManager can apply its retry policy on failure.
     */
    suspend fun runScheduledCheck() {
        checkForUpdates(force = true)
        activeCheckJob?.join()
    }

    /** Whether the most recent check finished without an error. */
    internal fun lastCheckSucceeded(): Boolean = _checkError.value == null

    private fun applyReleaseInfo(
        info: AppReleaseInfo,
        currentVersion: String
    ) {
        _latestReleaseInfo.value = info
        val available = SemVerComparator.isUpdateAvailable(currentVersion, info.tagName)
        _updateAvailable.value = available
        if (available) {
            notifyUpdateAvailable(info)
        } else {
            cancelUpdateNotification()
        }
    }

    /**
     * Posts a one-tap "update available" notification. Posted once per
     * release tag; the app UI still shows the update when notifications are
     * disabled or dismissed.
     */
    private fun notifyUpdateAvailable(info: AppReleaseInfo) {
        val context = appContext ?: return
        val prefs = getPrefs() ?: return
        if (prefs.getString(PREF_NOTIFIED_TAG, "") == info.tagName) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        runCatching {
            createUpdateChannel(manager)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_SHOW_UPDATE_DIALOG, true)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_swap)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(
                    context.getString(
                        R.string.update_notification_text,
                        info.displayVersion
                    )
                )
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        context.getString(
                            R.string.update_notification_text,
                            info.displayVersion
                        )
                    )
                )
                .setContentIntent(contentIntent)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_tile_swap),
                        context.getString(R.string.update_notification_action),
                        contentIntent
                    ).build()
                )
                // An explicit group keeps the update notification separate
                // from the service notification: the ranked auto-group shows
                // a summary whose tap only opens the app, not this intent.
                .setGroup(UPDATE_NOTIFICATION_GROUP)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
            prefs.edit().putString(PREF_NOTIFIED_TAG, info.tagName).apply()
            Log.i(TAG, "Posted update notification for ${info.tagName}")
        }.onFailure { e ->
            Log.e(TAG, "Failed to post the update notification", e)
        }
    }

    private fun createUpdateChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            appContext?.getString(R.string.update_channel_name) ?: "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description =
            appContext?.getString(R.string.update_channel_description).orEmpty()
        manager.createNotificationChannel(channel)
    }

    private fun cancelUpdateNotification() {
        val context = appContext ?: return
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(UPDATE_NOTIFICATION_ID)
        }
    }

    /** Called after the new APK has been installed successfully. */
    fun onUpdateInstalled() {
        cancelUpdateNotification()
        _updateAvailable.value = false
        runCatching {
            getPrefs()?.edit()?.putString(PREF_NOTIFIED_TAG, "")?.apply()
        }
    }

    private suspend fun fetchLatestRelease(releasesApiUrl: String): AppReleaseInfo? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(releasesApiUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "ScreenFlip-App")
                    connectTimeout = 8000
                    readTimeout = 10000
                }
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { reader ->
                        val sb = StringBuilder()
                        val buf = CharArray(4096)
                        while (sb.length < MAX_RELEASE_JSON_CHARS) {
                            val toRead = minOf(buf.size, MAX_RELEASE_JSON_CHARS - sb.length)
                            val n = reader.read(buf, 0, toRead)
                            if (n == -1) break
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }
                    parseGithubReleaseJson(body)
                } else {
                    Log.w(TAG, "GitHub API returned HTTP $responseCode")
                    null
                }
            } finally {
                connection?.disconnect()
            }
        }

    /** An APK asset attached to a GitHub release. */
    internal data class ApkAsset(
        val name: String,
        val url: String,
        val sizeBytes: Long
    )

    internal fun parseGithubReleaseJson(rawJson: String): AppReleaseInfo? =
        runCatching {
            val json = JSONObject(rawJson)
            val tagName = json.optString("tag_name", "").trim()
            val htmlUrl = json.optString("html_url", "").trim()
            if (tagName.isEmpty() || !isValidGithubReleaseUrl(htmlUrl)) {
                null
            } else {
                val apk = selectApkAsset(json.optJSONArray("assets"))
                AppReleaseInfo(
                    tagName = tagName,
                    htmlUrl = htmlUrl,
                    apkUrl = apk?.url,
                    apkSizeBytes = apk?.sizeBytes ?: 0L
                )
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to parse GitHub release JSON", e)
        }.getOrNull()

    /**
     * Picks the APK to download from the release assets: the canonical
     * `app-release.apk` first, then any asset with "release" in its name,
     * then the first APK. Non-HTTPS/non-GitHub URLs are ignored.
     */
    internal fun selectApkAsset(assets: JSONArray?): ApkAsset? {
        if (assets == null) return null
        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name", "").trim()
                val url = asset.optString("browser_download_url", "").trim()
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                if (!isValidGithubReleaseUrl(url)) continue
                add(
                    ApkAsset(
                        name = name,
                        url = url,
                        sizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }
        return candidates.firstOrNull { it.name.equals("app-release.apk", ignoreCase = true) }
            ?: candidates.firstOrNull { it.name.contains("release", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    internal fun resetForTesting() {
        synchronized(checkLock) {
            checkGeneration++
            activeCheckJob?.cancel()
            activeCheckJob = null
            prefsLoaded = false
            lastCheckTimeMs = 0L
            lastFailedCheckElapsedMs = 0L
            _autoUpdateCheckEnabled.value = true
            _updateAvailable.value = false
            _latestReleaseInfo.value = null
            _isChecking.value = false
            _checkError.value = null
        }
    }
}
