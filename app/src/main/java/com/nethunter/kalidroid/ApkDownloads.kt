package com.nethunter.kalidroid

import android.app.DownloadManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

internal const val TAG = "KaliDroid"
internal const val USER_AGENT = "KaliDroid-Installer"

/** A download in progress, or a verified APK waiting to be installed. Survives process death. */
internal data class PendingDownload(
    val isSelfUpdate: Boolean,
    val downloadId: Long,                 // DownloadManager id; -1 once the APK is verified
    val expectedSha256: String? = null,
    val targetLabel: String? = null,
    val packageName: String? = null,      // of the verified APK
    val versionCode: Long = 0L,           // of the verified APK
    val installLaunched: Boolean = false,
    val installBaseline: Long = 0L        // target's lastUpdateTime when the installer was first opened
) {
    val fileName: String
        get() = if (isSelfUpdate) ApkDownloads.UPDATE_FILE_NAME else ApkDownloads.SETUP_FILE_NAME
    val isVerified: Boolean get() = downloadId < 0
}

internal class DownloadStatus(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val isRunning: Boolean get() = status == DownloadManager.STATUS_RUNNING
    val isPaused: Boolean get() = status == DownloadManager.STATUS_PAUSED
}

/**
 * Runs APK downloads in the system DownloadManager, so they survive rotation, process death
 * and network drops. A finished download is copied into cacheDir/apk (the only folder
 * FileProvider shares), verified there, and installed from there. The pending download is
 * saved, so a recreated activity or restarted process picks up where it left off.
 */
internal class ApkDownloads(context: Context) {

    companion object {
        internal const val SETUP_FILE_NAME = "setup.apk"
        internal const val UPDATE_FILE_NAME = "kalidroid-update.apk"
        private const val APK_DIR = "apk"
        private const val PREFS = "pending_download"
        private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        // Process-wide, so an activity recreated mid-copy doesn't start a second copy,
        // and a cancel can't race the copy finishing.
        private val lock = Any()
        @Volatile private var finishingId = -1L
        @Volatile private var finishError: String? = null

        /**
         * Free bytes needed to finish a download: the rest of DownloadManager's file plus
         * our private copy of the whole APK, with some headroom.
         */
        internal fun bytesNeeded(totalBytes: Long, downloadedBytes: Long): Long =
            (totalBytes - downloadedBytes).coerceAtLeast(0L) + totalBytes + FREE_SPACE_MARGIN_BYTES
    }

    private val app = context.applicationContext
    private val downloadManager = app.getSystemService(DownloadManager::class.java)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val verifier = ApkVerifier(app)

    // ------------------------------------------------------------------
    // Saved state
    // ------------------------------------------------------------------

    fun load(): PendingDownload? {
        if (!prefs.contains("downloadId")) return null
        return PendingDownload(
            isSelfUpdate    = prefs.getBoolean("isSelfUpdate", false),
            downloadId      = prefs.getLong("downloadId", -1L),
            expectedSha256  = prefs.getString("expectedSha256", null),
            targetLabel     = prefs.getString("targetLabel", null),
            packageName     = prefs.getString("packageName", null),
            versionCode     = prefs.getLong("versionCode", 0L),
            installLaunched = prefs.getBoolean("installLaunched", false),
            installBaseline = prefs.getLong("installBaseline", 0L)
        )
    }

    internal fun save(pending: PendingDownload?) {
        // commit, not apply: the package installer may kill this process right after
        prefs.edit(commit = true) {
            clear()
            if (pending != null) {
                putBoolean("isSelfUpdate", pending.isSelfUpdate)
                putLong("downloadId", pending.downloadId)
                putString("expectedSha256", pending.expectedSha256)
                putString("targetLabel", pending.targetLabel)
                putString("packageName", pending.packageName)
                putLong("versionCode", pending.versionCode)
                putBoolean("installLaunched", pending.installLaunched)
                putLong("installBaseline", pending.installBaseline)
            }
        }
    }

    fun apkFile(pending: PendingDownload): File = File(apkDir(), pending.fileName)

    private fun apkDir(): File = File(app.cacheDir, APK_DIR).apply { mkdirs() }

    // ------------------------------------------------------------------
    // Downloading
    // ------------------------------------------------------------------

    /**
     * Replaces any pending download with a new one for [url].
     *
     * @throws Exception with a user-facing message if the download can't be started
     */
    fun start(
        url: String,
        isSelfUpdate: Boolean,
        targetLabel: String? = null,
        expectedSha256: String? = null
    ): PendingDownload {
        cancel()

        val fileName = if (isSelfUpdate) UPDATE_FILE_NAME else SETUP_FILE_NAME
        val downloadDir = app.getExternalFilesDir(null)
            ?: throw Exception(app.getString(R.string.error_storage_unavailable))
        // DownloadManager would otherwise save to "setup-1.apk"
        File(downloadDir, fileName).delete()

        val request = DownloadManager.Request(url.toUri())
            .addRequestHeader("User-Agent", USER_AGENT)
            .setTitle(app.getString(R.string.app_name))
            .setDescription(app.getString(if (isSelfUpdate) R.string.phase_updating else R.string.phase_downloading))
            // No "completed" notification: tapping it would open the APK without our checks
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(app, null, fileName)
        // Throws if e.g. the system Download Manager app is disabled
        val downloadId = downloadManager.enqueue(request)

        val pending = PendingDownload(isSelfUpdate, downloadId, expectedSha256, targetLabel)
        save(pending)
        return pending
    }

    fun query(downloadId: Long): DownloadStatus? {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (!c.moveToFirst()) return null
            return DownloadStatus(
                status          = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason          = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloadedBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes      = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
        return null
    }

    /** Bytes needed to finish [status], if the internal volume doesn't have them free; else null. */
    fun spaceShortfall(status: DownloadStatus): Long? {
        val needed = bytesNeeded(status.totalBytes, status.downloadedBytes)
        return needed.takeIf { allocatableBytes() < it }
    }

    /** Free space on the internal volume, counting cache the system would clear for us. */
    private fun allocatableBytes(): Long = runCatching {
        val storage = app.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(app.cacheDir))
    }.getOrElse { app.cacheDir.usableSpace }

    fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> app.getString(R.string.error_no_space_generic)
        DownloadManager.ERROR_DEVICE_NOT_FOUND   -> app.getString(R.string.error_storage_unavailable)
        in 400..599                              -> app.getString(R.string.error_http, reason)
        else                                     -> app.getString(R.string.error_download_failed, reason)
    }

    /** Drops a download DownloadManager reported as failed. */
    fun discardFailed(pending: PendingDownload) {
        downloadManager.remove(pending.downloadId)
        save(null)
    }

    /**
     * Forgets a download DownloadManager no longer knows about (e.g. removed from the Downloads
     * screen). Returns false if it was just finished instead, so there is nothing to forget.
     */
    fun forgetMissing(pending: PendingDownload): Boolean = synchronized(lock) {
        if (load()?.downloadId != pending.downloadId) return false
        save(null)
        true
    }

    fun cancel() {
        finishError = null
        synchronized(lock) {
            val pending = load() ?: return
            if (pending.downloadId >= 0) downloadManager.remove(pending.downloadId)
            apkFile(pending).delete()
            save(null)
        }
    }

    /**
     * Copies a finished download into cacheDir/apk and verifies it on a background thread.
     * The verified APK, or the error in [takeFinishError], shows up in the saved state.
     */
    fun finish(pending: PendingDownload) {
        synchronized(lock) {
            if (finishingId == pending.downloadId) return
            finishingId = pending.downloadId
        }

        Thread {
            val target = apkFile(pending)
            val partial = File(target.parentFile, target.name.removeSuffix(".apk") + ".partial.apk")
            try {
                ParcelFileDescriptor.AutoCloseInputStream(
                    downloadManager.openDownloadedFile(pending.downloadId)
                ).use { input ->
                    partial.outputStream().use { output -> input.copyTo(output, 65_536) }
                }
                val archive = verifier.verify(partial, pending.isSelfUpdate, pending.expectedSha256)

                synchronized(lock) {
                    // Cancelled while we were copying
                    if (load()?.downloadId != pending.downloadId) return@Thread
                    if (!partial.renameTo(target)) throw IOException(app.getString(R.string.error_move_failed))
                    save(
                        pending.copy(
                            downloadId  = -1L,
                            packageName = archive.packageName,
                            versionCode = PackageInfoCompat.getLongVersionCode(archive)
                        )
                    )
                    downloadManager.remove(pending.downloadId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not finish download ${pending.downloadId}", e)
                synchronized(lock) {
                    if (load()?.downloadId == pending.downloadId) {
                        save(null)
                        downloadManager.remove(pending.downloadId)
                        finishError = e.message ?: app.getString(R.string.error_unknown)
                    }
                }
            } finally {
                partial.delete()
                finishingId = -1L
            }
        }.start()
    }

    /** The error from the last failed [finish], once. */
    fun takeFinishError(): String? = finishError.also { finishError = null }

    // ------------------------------------------------------------------
    // Installing
    // ------------------------------------------------------------------

    /** Records that the installer was opened, remembering how the target looked before. */
    fun markInstallLaunched(pending: PendingDownload): PendingDownload {
        val baseline = if (pending.installLaunched) {
            pending.installBaseline
        } else {
            pending.packageName?.let { app.installedPackage(it) }?.lastUpdateTime ?: 0L
        }
        return pending.copy(installLaunched = true, installBaseline = baseline).also { save(it) }
    }

    /**
     * True once the APK has actually been installed since we opened the installer. The
     * same version may already have been installed before, so the version alone isn't proof.
     */
    fun isInstalled(pending: PendingDownload): Boolean {
        val info = pending.packageName?.let { app.installedPackage(it) } ?: return false
        return PackageInfoCompat.getLongVersionCode(info) >= pending.versionCode &&
            info.lastUpdateTime > pending.installBaseline
    }

    /** Deletes a verified APK that is installed or no longer wanted, and forgets it. */
    fun discardApk(pending: PendingDownload) {
        apkFile(pending).delete()
        save(null)
    }

    /** Deletes APKs and partial files from earlier runs that no pending download refers to. */
    fun cleanupStaleFiles() {
        // A recreated activity: the copy in progress owns these files
        if (finishingId >= 0) return

        val pending = load()
        // Installer versions before 2.3 downloaded straight into cacheDir
        app.cacheDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".tmp")) }
            ?.forEach { it.delete() }
        apkDir().listFiles()
            ?.filter { pending == null || !pending.isVerified || it.name != pending.fileName }
            ?.forEach { it.delete() }
        // DownloadManager's files, unless it is still writing one
        if (pending == null || pending.isVerified) {
            app.getExternalFilesDir(null)?.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        }
    }
}
