package com.screenflip.app.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.screenflip.app.R

/**
 * In-app update dialog: download button, download progress and an install
 * button that hands the APK to the system installer.
 */
@Composable
fun UpdateDialog(
    info: AppReleaseInfo,
    downloadState: UpdateInstaller.DownloadState,
    installState: UpdateInstaller.InstallState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onAllowInstall: () -> Unit,
    onOpenGithub: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_dialog_message, info.displayVersion))
                if (info.apkSizeBytes > 0L) {
                    Text(
                        text = stringResource(
                            R.string.update_dialog_size,
                            Formatter.formatShortFileSize(context, info.apkSizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (downloadState) {
                    is UpdateInstaller.DownloadState.Downloading -> {
                        Text(stringResource(R.string.update_dialog_downloading))
                        val fraction = downloadState.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = if (downloadState.totalBytes > 0L) {
                                stringResource(
                                    R.string.update_dialog_download_progress,
                                    Formatter.formatShortFileSize(
                                        context,
                                        downloadState.bytes
                                    ),
                                    Formatter.formatShortFileSize(
                                        context,
                                        downloadState.totalBytes
                                    )
                                )
                            } else {
                                Formatter.formatShortFileSize(context, downloadState.bytes)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateInstaller.DownloadState.Ready ->
                        Text(stringResource(R.string.update_dialog_ready))
                    is UpdateInstaller.DownloadState.Failed -> Text(
                        text = downloadState.message,
                        color = MaterialTheme.colorScheme.error
                    )
                    UpdateInstaller.DownloadState.Idle -> Unit
                }

                if (installState is UpdateInstaller.InstallState.NeedsPermission) {
                    Text(
                        text = stringResource(R.string.update_dialog_permission_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (installState is UpdateInstaller.InstallState.Installing) {
                    Text(
                        text = stringResource(R.string.update_dialog_installing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (installState is UpdateInstaller.InstallState.Failed) {
                    Text(
                        text = installState.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when {
                !info.hasApk -> Button(onClick = onOpenGithub) {
                    Text(stringResource(R.string.update_dialog_open_github))
                }
                downloadState is UpdateInstaller.DownloadState.Downloading ->
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.update_dialog_cancel_download))
                    }
                installState is UpdateInstaller.InstallState.NeedsPermission ->
                    Button(onClick = onAllowInstall) {
                        Text(stringResource(R.string.update_dialog_allow))
                    }
                downloadState is UpdateInstaller.DownloadState.Ready -> Button(
                    onClick = onInstall,
                    enabled = installState !is UpdateInstaller.InstallState.Installing
                ) {
                    Text(
                        if (installState is UpdateInstaller.InstallState.Installing) {
                            stringResource(R.string.update_dialog_installing)
                        } else {
                            stringResource(R.string.update_dialog_install)
                        }
                    )
                }
                downloadState is UpdateInstaller.DownloadState.Failed -> Button(
                    onClick = onDownload
                ) {
                    Text(stringResource(R.string.update_dialog_retry))
                }
                else -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.update_dialog_download))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_dialog_later))
            }
        }
    )
}
