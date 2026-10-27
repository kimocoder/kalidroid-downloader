package com.nethunter.kalidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateTest {
    private val validSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun parseUpdateInfo_readsCiManifest() {
        val info = SelfUpdate.parseUpdateInfo(
            """
            {
              "versionCode": 42,
              "versionName": "2.3",
              "commit": "930ad6fca68d2928a8d4dd4c4611a7917cd17433",
              "sha256": "${validSha256.uppercase()}",
              "size": 7299828,
              "url": "https://github.com/kimocoder/kalidroid-downloader/releases/download/latest/kalidroid.apk"
            }
            """.trimIndent()
        )
        assertNotNull(info)
        assertEquals(42L, info!!.versionCode)
        assertEquals(validSha256, info.sha256)
        assertEquals(
            "https://github.com/kimocoder/kalidroid-downloader/releases/download/latest/kalidroid.apk",
            info.url
        )
    }

    @Test
    fun parseUpdateInfo_rejectsInvalidManifests() {
        val url = "https://example.com/kalidroid.apk"
        assertNull(SelfUpdate.parseUpdateInfo("not json"))
        assertNull(SelfUpdate.parseUpdateInfo("""{"url":"$url","sha256":"$validSha256"}"""))
        assertNull(SelfUpdate.parseUpdateInfo("""{"versionCode":0,"url":"$url","sha256":"$validSha256"}"""))
        assertNull(
            SelfUpdate.parseUpdateInfo(
                """{"versionCode":5,"url":"http://example.com/kalidroid.apk","sha256":"$validSha256"}"""
            )
        )
        assertNull(SelfUpdate.parseUpdateInfo("""{"versionCode":5,"url":"$url","sha256":"abc123"}"""))
        assertNull(
            SelfUpdate.parseUpdateInfo(
                """{"versionCode":5,"url":"$url","sha256":"${"z".repeat(64)}"}"""
            )
        )
    }

    @Test
    fun isSnoozed_hidesThatBuildForADay() {
        val day = 24 * 60 * 60 * 1000L
        val laterAt = 1_000_000_000L

        assertTrue(SelfUpdate.isSnoozed(57, 57, laterAt, laterAt + day - 1))
        assertTrue(SelfUpdate.isSnoozed(56, 57, laterAt, laterAt + 1))
        // A newer build than the one put off, or after a day, prompts again
        assertFalse(SelfUpdate.isSnoozed(58, 57, laterAt, laterAt + 1))
        assertFalse(SelfUpdate.isSnoozed(57, 57, laterAt, laterAt + day))
        // Never snoozed, or the clock went backwards
        assertFalse(SelfUpdate.isSnoozed(57, 0, 0, laterAt))
        assertFalse(SelfUpdate.isSnoozed(57, 57, laterAt, laterAt - 1))
    }
}
