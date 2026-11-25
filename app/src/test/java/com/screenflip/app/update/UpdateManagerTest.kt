package com.screenflip.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `parseGithubReleaseJson extracts tagName and htmlUrl`() {
        val json = """
            {
              "tag_name": "v1.2.3",
              "name": "ScreenFlip v1.2.3",
              "html_url": "https://github.com/Stezkoy/ScreenFlip/releases/tag/v1.2.3"
            }
        """.trimIndent()

        val info = UpdateManager.parseGithubReleaseJson(json)
        assertNotNull(info)
        requireNotNull(info)
        assertEquals("v1.2.3", info.tagName)
        assertEquals(
            "https://github.com/Stezkoy/ScreenFlip/releases/tag/v1.2.3",
            info.htmlUrl
        )
    }

    @Test
    fun `parseGithubReleaseJson returns null on blank tag missing url non-GitHub URL or invalid JSON`() {
        assertNull(UpdateManager.parseGithubReleaseJson("""{"tag_name": "   ", "html_url": "https://github.com/Stezkoy/ScreenFlip"}"""))
        assertNull(UpdateManager.parseGithubReleaseJson("""{"tag_name": "v1.0.0", "html_url": ""}"""))
        assertNull(UpdateManager.parseGithubReleaseJson("""{"tag_name": "v1.0.0", "html_url": "http://github.com/Stezkoy/ScreenFlip/releases"}"""))
        assertNull(UpdateManager.parseGithubReleaseJson("""{"tag_name": "v1.0.0", "html_url": "https://evil.example.com/github.com/releases"}"""))
        assertNull(UpdateManager.parseGithubReleaseJson("""{"tag_name": "v1.0.0", "html_url": "intent://scan/#Intent;scheme=zxing;end"}"""))
        assertNull(UpdateManager.parseGithubReleaseJson("""{"name": "No Tag"}"""))
        assertNull(UpdateManager.parseGithubReleaseJson("not valid json"))
    }

    @Test
    fun `parseGithubReleaseJson picks the app-release APK asset`() {
        val json = """
            {
              "tag_name": "v1.2.3",
              "html_url": "https://github.com/Stezkoy/ScreenFlip/releases/tag/v1.2.3",
              "assets": [
                {"name": "notes.txt", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1.2.3/notes.txt", "size": 10},
                {"name": "other.apk", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1.2.3/other.apk", "size": 20},
                {"name": "app-release.apk", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1.2.3/app-release.apk", "size": 12345}
              ]
            }
        """.trimIndent()

        val info = UpdateManager.parseGithubReleaseJson(json)
        assertNotNull(info)
        requireNotNull(info)
        assertEquals(
            "https://github.com/Stezkoy/ScreenFlip/releases/download/v1.2.3/app-release.apk",
            info.apkUrl
        )
        assertEquals(12345L, info.apkSizeBytes)
        assertTrue(info.hasApk)
    }

    @Test
    fun `parseGithubReleaseJson ignores APK assets hosted outside GitHub`() {
        val json = """
            {
              "tag_name": "v1.2.3",
              "html_url": "https://github.com/Stezkoy/ScreenFlip/releases/tag/v1.2.3",
              "assets": [
                {"name": "app-release.apk", "browser_download_url": "https://evil.example.com/app-release.apk", "size": 12345},
                {"name": "notes.txt", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1.2.3/notes.txt", "size": 10}
              ]
            }
        """.trimIndent()

        val info = UpdateManager.parseGithubReleaseJson(json)
        assertNotNull(info)
        requireNotNull(info)
        assertNull(info.apkUrl)
        assertEquals(0L, info.apkSizeBytes)
        assertFalse(info.hasApk)
    }

    @Test
    fun `selectApkAsset prefers a release APK and falls back to any APK`() {
        val assets = org.json.JSONArray(
            """
            [
              {"name": "ScreenFlip-debug.apk", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1/app-debug.apk", "size": 1},
              {"name": "screenflip-release.apk", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1/app-release-signed.apk", "size": 2}
            ]
            """.trimIndent()
        )
        assertEquals("screenflip-release.apk", UpdateManager.selectApkAsset(assets)?.name)

        val onlyDebug = org.json.JSONArray(
            """
            [{"name": "ScreenFlip-debug.apk", "browser_download_url": "https://github.com/Stezkoy/ScreenFlip/releases/download/v1/app-debug.apk", "size": 1}]
            """.trimIndent()
        )
        assertEquals("ScreenFlip-debug.apk", UpdateManager.selectApkAsset(onlyDebug)?.name)
    }

    @Test
    fun `isValidGithubReleaseUrl accepts only https GitHub URLs`() {
        assertTrue(UpdateManager.isValidGithubReleaseUrl("https://github.com/Stezkoy/ScreenFlip/releases/tag/v1.0.0"))
        assertTrue(UpdateManager.isValidGithubReleaseUrl("https://api.github.com/repos/Stezkoy/ScreenFlip/releases"))
        assertFalse(UpdateManager.isValidGithubReleaseUrl("https://screenflip.github.io/releases"))
        assertFalse(UpdateManager.isValidGithubReleaseUrl("http://github.com/Stezkoy/ScreenFlip/releases"))
        assertFalse(UpdateManager.isValidGithubReleaseUrl("https://attacker.com/github.com"))
        assertFalse(UpdateManager.isValidGithubReleaseUrl(""))
        assertFalse(UpdateManager.isValidGithubReleaseUrl("   "))
    }

    @Test
    fun `checkForUpdates without init does not start a check`() {
        UpdateManager.resetForTesting()
        UpdateManager.checkForUpdates(force = false)
        assertFalse(UpdateManager.isChecking.value)
        assertNull(UpdateManager.checkError.value)
    }

    @Test
    fun `resetForTesting clears state`() {
        UpdateManager.resetForTesting()
        assertTrue(UpdateManager.autoUpdateCheckEnabled.value)
        assertFalse(UpdateManager.updateAvailable.value)
        assertNull(UpdateManager.latestReleaseInfo.value)
        assertFalse(UpdateManager.isChecking.value)
        assertNull(UpdateManager.checkError.value)
    }
}
