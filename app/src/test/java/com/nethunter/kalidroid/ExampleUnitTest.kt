package com.nethunter.kalidroid

import org.junit.Test

import org.junit.Assert.*
import java.io.File

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    private val validSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun parseUpdateInfo_readsCiManifest() {
        val info = MainActivity.parseUpdateInfo(
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
        assertNull(MainActivity.parseUpdateInfo("not json"))
        assertNull(MainActivity.parseUpdateInfo("""{"url":"$url","sha256":"$validSha256"}"""))
        assertNull(MainActivity.parseUpdateInfo("""{"versionCode":0,"url":"$url","sha256":"$validSha256"}"""))
        assertNull(
            MainActivity.parseUpdateInfo(
                """{"versionCode":5,"url":"http://example.com/kalidroid.apk","sha256":"$validSha256"}"""
            )
        )
        assertNull(MainActivity.parseUpdateInfo("""{"versionCode":5,"url":"$url","sha256":"abc123"}"""))
        assertNull(
            MainActivity.parseUpdateInfo(
                """{"versionCode":5,"url":"$url","sha256":"${"z".repeat(64)}"}"""
            )
        )
    }

    @Test
    fun sha256Hex_hashesBytes() {
        assertEquals(validSha256, MainActivity.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun isSignedByAny_requiresATrustedSigner() {
        val trusted = "30b9525c0e8f3b7d32de877cb41ff8651dabc9dbf263555b2ffd200bf1f894ee"
        val other = "eb9e6a88121a66cd5acc7beb20c2e67415085b556018bf3616f3448df651ff4f"

        assertTrue(MainActivity.isSignedByAny(setOf(trusted), setOf(trusted)))
        // Rotated key: lineage still contains the trusted certificate
        assertTrue(MainActivity.isSignedByAny(setOf(trusted, other), setOf(trusted)))
        assertFalse(MainActivity.isSignedByAny(setOf(other), setOf(trusted)))
        // Unsigned archive or unreadable installed signers must never match
        assertFalse(MainActivity.isSignedByAny(emptySet(), setOf(trusted)))
        assertFalse(MainActivity.isSignedByAny(setOf(trusted), emptySet()))
    }

    @Test
    fun sha256Hex_hashesFileContents() {
        val file = File.createTempFile("sha256", ".bin")
        try {
            file.writeText("abc")
            assertEquals(validSha256, MainActivity.sha256Hex(file))
        } finally {
            file.delete()
        }
    }
}
