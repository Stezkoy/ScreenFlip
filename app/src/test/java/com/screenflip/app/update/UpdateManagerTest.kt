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
