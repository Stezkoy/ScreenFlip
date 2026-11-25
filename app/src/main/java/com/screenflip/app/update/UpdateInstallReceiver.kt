package com.screenflip.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the result of a [PackageInstaller] session started by
 * [UpdateInstaller]. The system also sends [PackageInstaller.STATUS_PENDING_USER_ACTION]
 * here, and that intent must be launched to show the install confirmation.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The installer app must be launched for the user to confirm.
            val confirmation = intent.getParcelableExtra(
                Intent.EXTRA_INTENT,
                Intent::class.java
            )
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmation) }
                    .onFailure { e ->
                        Log.e(TAG, "Could not show the install confirmation", e)
                        UpdateInstaller.onInstallResult(
                            PackageInstaller.STATUS_FAILURE,
                            e.message
                        )
                    }
            }
            return
        }
        UpdateInstaller.onInstallResult(
            status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        )
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.screenflip.app.INSTALL_STATUS"
    }
}
