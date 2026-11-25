package com.screenflip.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import com.screenflip.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the APK of a GitHub release and installs it through
 * [PackageInstaller], without leaving the app.
 *
 * Both steps expose their progress through StateFlows so the Compose dialog
 * can show a download button, a progress bar and the install button.
 */
object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 256L * 1024
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val UPDATE_DIR = "updates"

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val bytes: Long, val totalBytes: Long) : DownloadState {
            /** 0..1, or null when the server did not report a size. */
            val fraction: Float?
                get() = if (totalBytes > 0L) {
                    (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else {
                    null
                }
        }
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    sealed interface InstallState {
        data object Idle : InstallState
        /** The user must allow "install unknown apps" for ScreenFlip. */
        data object NeedsPermission : InstallState
        data object Installing : InstallState
        data class Failed(val message: String) : InstallState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** Starts (or restarts) the APK download for [info]. */
    fun download(context: Context, info: AppReleaseInfo) {
        val appContext = context.applicationContext
        val url = info.apkUrl
        if (url.isNullOrBlank()) {
            _downloadState.value =
                DownloadState.Failed(appContext.getString(R.string.update_no_apk))
            return
        }
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            val directory = File(appContext.cacheDir, UPDATE_DIR).apply { mkdirs() }
            val target = File(directory, fileName(info))
            val existing = readApkInfo(appContext, target)
            if (existing?.packageName == appContext.packageName) {
                _downloadState.value = DownloadState.Ready(target)
                return@launch
            }
            target.delete()

            val part = File(directory, "${target.name}.part")
            part.delete()
            _downloadState.value = DownloadState.Downloading(0L, info.apkSizeBytes)
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "ScreenFlip-App")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                }
                activeConnection = connection
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) throw IOException("HTTP $responseCode")

                val contentLength = connection.contentLengthLong
                val totalBytes = if (contentLength > 0L) contentLength else info.apkSizeBytes
                if (totalBytes > MAX_APK_BYTES) throw IOException("APK is too large")

                var written = 0L
                var lastReported = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written > MAX_APK_BYTES) throw IOException("APK is too large")
                            if (written - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = written
                                _downloadState.value =
                                    DownloadState.Downloading(written, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                _downloadState.value = DownloadState.Downloading(written, totalBytes)

                if (totalBytes > 0L && part.length() != totalBytes) {
                    throw IOException("Incomplete download")
                }
                if (!part.renameTo(target)) {
                    throw IOException("Could not move the downloaded APK")
                }
                val apkInfo = readApkInfo(appContext, target)
                if (apkInfo?.packageName != appContext.packageName) {
                    target.delete()
                    throw IOException("Downloaded file is not a ScreenFlip APK")
                }
                Log.i(TAG, "Downloaded ${info.tagName} (${target.length()} bytes)")
                _downloadState.value = DownloadState.Ready(target)
            } catch (e: CancellationException) {
                part.delete()
                _downloadState.value = DownloadState.Idle
                throw e
            } catch (e: Exception) {
                part.delete()
                Log.e(TAG, "Download failed", e)
                // Technical details stay in logcat; the dialog shows a
                // localized message the user can act on.
                _downloadState.value = DownloadState.Failed(
                    appContext.getString(R.string.update_download_failed)
                )
            } finally {
                activeConnection = null
                connection?.disconnect()
                downloadJob = null
            }
        }
    }

    /** Cancels an in-flight download; the partial file is removed. */
    fun cancelDownload() {
        activeConnection?.disconnect()
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
    }

    /**
     * Installs the downloaded APK. Without the "install unknown apps"
     * permission the state becomes [InstallState.NeedsPermission] and the UI
     * is expected to send the user to the system settings.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val ready = _downloadState.value as? DownloadState.Ready ?: run {
            _installState.value =
                InstallState.Failed(appContext.getString(R.string.update_install_no_file))
            return
        }
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _installState.value = InstallState.NeedsPermission
            return
        }
        if (!ready.file.exists()) {
            _downloadState.value = DownloadState.Idle
            _installState.value =
                InstallState.Failed(appContext.getString(R.string.update_install_no_file))
            return
        }
        _installState.value = InstallState.Installing
        scope.launch {
            try {
                val installer = appContext.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setAppPackageName(appContext.packageName)
                }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    ready.file.inputStream().use { input ->
                        session.openWrite("base.apk", 0L, ready.file.length()).use { output ->
                            input.copyTo(output, BUFFER_BYTES)
                            session.fsync(output)
                        }
                    }
                    val callback = Intent(appContext, UpdateInstallReceiver::class.java)
                        .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                    val pendingIntent = PendingIntent.getBroadcast(
                        appContext,
                        1,
                        callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pendingIntent.intentSender)
                }
                Log.i(TAG, "Install session committed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                _installState.value = InstallState.Failed(
                    appContext.getString(R.string.update_install_failed)
                )
            }
        }
    }

    /** Result reported by [UpdateInstallReceiver]. */
    internal fun onInstallResult(status: Int, message: String?) {
        val context = appContextOrNull() ?: return
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "APK installed")
                UpdateManager.onUpdateInstalled()
                _installState.value = InstallState.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Install canceled by the user")
                _installState.value = InstallState.Idle
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.w(TAG, "Install blocked: $message")
                _installState.value = InstallState.NeedsPermission
            }
            else -> {
                Log.e(TAG, "Install failed: status=$status message=$message")
                _installState.value = InstallState.Failed(
                    context.getString(R.string.update_install_failed)
                )
            }
        }
    }

    /** Called when the UI sent the user to the "unknown apps" settings. */
    fun clearInstallState() {
        _installState.value = InstallState.Idle
    }

    private fun appContextOrNull(): Context? = try {
        com.screenflip.app.ScreenFlipApp.appContext
    } catch (_: Throwable) {
        null
    }

    private fun fileName(info: AppReleaseInfo): String {
        val tag = info.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "screenflip-$tag.apk"
    }

    private fun readApkInfo(context: Context, file: File): android.content.pm.PackageInfo? =
        runCatching {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L)
            )
        }.getOrNull()
}
