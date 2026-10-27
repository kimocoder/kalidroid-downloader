package com.nethunter.kalidroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Runs the APK checks against this app's own, really signed, APK. */
@RunWith(AndroidJUnit4::class)
class ApkVerifierDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val verifier = ApkVerifier(context)
    private val ownApk = File(context.applicationInfo.sourceDir)
    private val scratch = File(context.cacheDir, "verifier-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    @Test
    fun archiveSignersMatchInstalledSigners() {
        val installed = verifier.installedSigners()
        assertFalse(installed.isEmpty())
        assertEquals(installed, verifier.archiveSigners(ownApk))
    }

    @Test
    fun rejectsSelfUpdateThatIsNotNewer() {
        assertRejected(context.getString(R.string.error_update_not_newer)) {
            verifier.verify(ownApk, isSelfUpdate = true, expectedSha256 = null)
        }
    }

    @Test
    fun matchingChecksumPassesOnToTheVersionCheck() {
        val sha256 = ApkVerifier.sha256Hex(ownApk)
        assertRejected(context.getString(R.string.error_update_not_newer)) {
            verifier.verify(ownApk, isSelfUpdate = true, expectedSha256 = sha256)
        }
    }

    @Test
    fun rejectsChecksumMismatch() {
        assertRejected(context.getString(R.string.error_checksum)) {
            verifier.verify(ownApk, isSelfUpdate = true, expectedSha256 = "0".repeat(64))
        }
    }

    @Test
    fun rejectsSetupFromAnotherPublisher() {
        // This app is neither setup.apk's package nor signed with its key
        assertRejected(context.getString(R.string.error_wrong_publisher)) {
            verifier.verify(ownApk, isSelfUpdate = false, expectedSha256 = null)
        }
    }

    @Test
    fun rejectsFileThatIsNotAnApk() {
        val file = File(scratch, "hello.apk").apply { writeText("hello") }
        assertRejected(context.getString(R.string.error_not_apk, "68 65 6C 6C")) {
            verifier.verify(file, isSelfUpdate = false, expectedSha256 = null)
        }
    }

    @Test
    fun rejectsTamperedApk() {
        // Still starts like a zip, but the signed contents changed
        val file = ownApk.copyTo(File(scratch, "tampered.apk"))
        RandomAccessFile(file, "rw").use {
            it.seek(file.length() / 2)
            it.write(ByteArray(64) { 0x55 })
        }
        assertRejected(context.getString(R.string.error_corrupted)) {
            verifier.verify(file, isSelfUpdate = false, expectedSha256 = null)
        }
    }

    private fun assertRejected(expectedMessage: String, verify: () -> Unit) {
        try {
            verify()
            fail("Expected verification to fail with \"$expectedMessage\"")
        } catch (e: Exception) {
            assertEquals(expectedMessage, e.message)
        }
    }
}
