package com.screenflip.app.update

/**
 * A parsed GitHub release: its version tag, the page to open in a browser and
 * (when the release ships one) the downloadable APK asset.
 */
data class AppReleaseInfo(
    val tagName: String,
    val htmlUrl: String,
    val apkUrl: String? = null,
    val apkSizeBytes: Long = 0L
) {
    /** True when the release has an APK that can be downloaded in-app. */
    val hasApk: Boolean get() = !apkUrl.isNullOrBlank()

    /** Tag without the leading "v" ("v1.0.2" -> "1.0.2"), for display. */
    val displayVersion: String
        get() = tagName.trim().removePrefix("v").removePrefix("V")
}
