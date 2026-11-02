package com.nethunter.kalidroid

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The screen picks up the saved download state, as after rotation or a process restart. */
@RunWith(AndroidJUnit4::class)
class MainActivityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloads = ApkDownloads(context)

    @Before
    fun setUp() {
        // Keep a published installer update from prompting over the screen under test
        SelfUpdate(context).snooze(Long.MAX_VALUE)
        downloads.cancel()
    }

    @After
    fun tearDown() {
        downloads.cancel()
    }

    @Test
    fun idleScreenOffersDownload() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnDownload)).check(matches(isDisplayed()))
            onView(withId(R.id.progressPanel)).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun verifiedApkWaitsWithInstallButtonAcrossRecreation() {
        // A verified APK whose installer was opened and then dismissed
        val pending = PendingDownload(
            isSelfUpdate = false,
            downloadId = -1L,
            packageName = "com.example.not.installed",
            versionCode = 1L,
            installLaunched = true
        )
        downloads.save(pending)
        downloads.apkFile(pending).writeText("apk")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.btnInstall)).check(matches(isDisplayed()))
            onView(withId(R.id.tvStatus)).check(matches(withText(R.string.status_ready)))

            scenario.recreate()

            onView(withId(R.id.btnInstall)).check(matches(isDisplayed()))
            onView(withId(R.id.tvStatus)).check(matches(withText(R.string.status_ready)))
        }
    }

    @Test
    fun apkPurgedFromCacheFallsBackToIdle() {
        // Saved as verified, but the system cleared the cache since
        downloads.save(
            PendingDownload(
                isSelfUpdate = false,
                downloadId = -1L,
                packageName = "com.example.not.installed",
                versionCode = 1L,
                installLaunched = true
            )
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnDownload)).check(matches(isDisplayed()))
        }
        assertNull(downloads.load())
    }
}
