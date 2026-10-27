package com.nethunter.kalidroid

import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ApkDownloadsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloads = ApkDownloads(context)
    private val verified = PendingDownload(
        isSelfUpdate = false,
        downloadId = -1L,
        packageName = "com.example.not.installed",
        versionCode = 1L
    )

    @Before
    @After
    fun reset() {
        downloads.cancel()
    }

    @Test
    fun savedStateRoundTrips() {
        val pending = PendingDownload(
            isSelfUpdate = true,
            downloadId = 42L,
            expectedSha256 = "ab".repeat(32),
            targetLabel = "build 7",
            packageName = "com.example",
            versionCode = 7L,
            installLaunched = true,
            installBaseline = 123L
        )
        downloads.save(pending)
        assertEquals(pending, downloads.load())

        downloads.save(null)
        assertNull(downloads.load())
    }

    @Test
    fun cleanupKeepsOnlyTheVerifiedApk() {
        downloads.save(verified)
        val apk = downloads.apkFile(verified).apply { writeText("apk") }
        val apkDir = apk.parentFile!!
        val otherApk = File(apkDir, ApkDownloads.UPDATE_FILE_NAME).apply { writeText("old update") }
        val partial = File(apkDir, "setup.partial.apk").apply { writeText("killed mid-copy") }
        val legacyApk = File(context.cacheDir, "setup.apk").apply { writeText("from 2.2") }
        val legacyTmp = File(context.cacheDir, "setup.apk.123.tmp").apply { writeText("from 2.2") }
        val downloadLeftover = File(context.getExternalFilesDir(null), "setup.apk").apply { writeText("dm") }

        downloads.cleanupStaleFiles()

        assertTrue(apk.exists())
        assertFalse(otherApk.exists())
        assertFalse(partial.exists())
        assertFalse(legacyApk.exists())
        assertFalse(legacyTmp.exists())
        assertFalse(downloadLeftover.exists())
    }

    @Test
    fun cleanupLeavesARunningDownloadAlone() {
        downloads.save(PendingDownload(isSelfUpdate = false, downloadId = 99L))
        val inProgress = File(context.getExternalFilesDir(null), "setup.apk").apply { writeText("in progress") }

        downloads.cleanupStaleFiles()

        assertTrue(inProgress.exists())
        inProgress.delete()
    }

    @Test
    fun installCountsOnlyOnceThePackageChanges() {
        // This app stands in for the target: it's installed, at a known version
        val installed = context.installedPackage()!!
        val pending = PendingDownload(
            isSelfUpdate = true,
            downloadId = -1L,
            packageName = context.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(installed)
        )

        val launched = downloads.markInstallLaunched(pending)
        assertEquals(installed.lastUpdateTime, launched.installBaseline)
        // The same version was already there before the installer opened: not done yet
        assertFalse(downloads.isInstalled(launched))
        // Installed (again) after the installer opened
        assertTrue(downloads.isInstalled(launched.copy(installBaseline = installed.lastUpdateTime - 1)))
        // Opening the installer again keeps the first baseline
        assertEquals(5L, downloads.markInstallLaunched(launched.copy(installBaseline = 5L)).installBaseline)
    }

    @Test
    fun cancelDeletesAVerifiedApk() {
        downloads.save(verified)
        val apk = downloads.apkFile(verified).apply { writeText("apk") }

        downloads.cancel()

        assertNull(downloads.load())
        assertFalse(apk.exists())
    }
}
