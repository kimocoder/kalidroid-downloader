package com.nethunter.kalidroid

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkDownloadsTest {
    private val mb = 1024L * 1024
    private val margin = 64 * mb

    @Test
    fun bytesNeeded_coversRemainingDownloadPlusCopy() {
        // Nothing downloaded yet: DownloadManager's file and our copy
        assertEquals(343 * mb * 2 + margin, ApkDownloads.bytesNeeded(343 * mb, 0))
        // Half done: only the rest of the download is still to come
        assertEquals(100 * mb + 200 * mb + margin, ApkDownloads.bytesNeeded(200 * mb, 100 * mb))
        // DownloadManager over-reporting never makes the remainder negative
        assertEquals(10 * mb + margin, ApkDownloads.bytesNeeded(10 * mb, 12 * mb))
    }
}
