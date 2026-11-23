package com.screenflip.app.update

/** A parsed GitHub release: its version tag and the page to open for download. */
data class AppReleaseInfo(
    val tagName: String,
    val htmlUrl: String
)
