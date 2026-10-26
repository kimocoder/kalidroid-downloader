package com.nethunter.kalidroid

import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KaliDroid"
        private const val APK_URL = "https://kalidroid.com/setup.apk"
        // Published by CI next to the rolling APK: {"versionCode", "url", "sha256", ...}
        private const val SELF_UPDATE_MANIFEST_URL =
            "https://github.com/kimocoder/kalidroid-downloader/releases/download/latest/version.json"
        private const val USER_AGENT = "KaliDroid-Installer"
        private const val FILE_PROVIDER_AUTHORITY = "com.nethunter.kalidroid.provider"

        // setup.apk must be one of these packages, signed by one of these certificates
        // (SHA-256, as printed by `apksigner verify --print-certs`). Add a new key here
        // and ship that installer before kalidroid.com starts serving APKs signed with it.
        // Keep the package names in sync with <queries> in AndroidManifest.xml.
        private val SETUP_PACKAGE_NAMES = setOf("com.excp.kalidroid.debug")
        private val SETUP_SIGNER_SHA256 = setOf(
            "30b9525c0e8f3b7d32de877cb41ff8651dabc9dbf263555b2ffd200bf1f894ee"
        )

        // Some Android versions only collect (and verify) an archive's certificates
        // when GET_SIGNATURES is set, so always request it alongside the newer flag.
        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS = PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0

        // Downloads run in the system DownloadManager, so they survive rotation, process
        // death and network drops. The finished APK is copied into cacheDir/apk (the only
        // folder FileProvider shares), verified there, and installed from there.
        private const val APK_DIR = "apk"
        private const val SETUP_FILE_NAME = "setup.apk"
        private const val UPDATE_FILE_NAME = "kalidroid-update.apk"
        private const val PENDING_PREFS = "pending_download"
        private const val UPDATE_PREFS = "self_update"
        private const val POLL_INTERVAL_MS = 500L
        private const val UPDATE_SNOOZE_MS = 24 * 60 * 60 * 1000L
        private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        // Process-wide, so an activity recreated mid-copy doesn't start a second copy,
        // and a cancel can't race the copy finishing.
        private val finalizeLock = Any()
        @Volatile private var finalizingId = -1L
        @Volatile private var finalizeError: String? = null

        /** True if any of [signers] (SHA-256 certificate digests) is in [trusted]. */
        internal fun isSignedByAny(signers: Set<String>, trusted: Set<String>): Boolean =
            signers.any { it in trusted }

        /**
         * Free bytes needed to finish a download: the rest of DownloadManager's file plus
         * our private copy of the whole APK, with some headroom.
         */
        internal fun bytesNeeded(totalBytes: Long, downloadedBytes: Long): Long =
            (totalBytes - downloadedBytes).coerceAtLeast(0L) + totalBytes + FREE_SPACE_MARGIN_BYTES

        /** "Later" on the update prompt hides that build (and older ones) for a day. */
        internal fun isUpdateSnoozed(
            versionCode: Long,
            snoozedVersionCode: Long,
            snoozedAtMs: Long,
            nowMs: Long
        ): Boolean = versionCode <= snoozedVersionCode && nowMs - snoozedAtMs in 0 until UPDATE_SNOOZE_MS

        internal fun parseUpdateInfo(json: String): UpdateInfo? {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val versionCode = obj.optLong("versionCode", 0L)
            val url = obj.optString("url", "").trim()
            val sha256 = obj.optString("sha256", "").trim().lowercase(Locale.US)

            if (versionCode <= 0L) return null
            if (!url.startsWith("https://")) return null
            if (sha256.length != 64 || !sha256.all { it.isDigit() || it in 'a'..'f' }) return null
            return UpdateInfo(versionCode, url, sha256)
        }

        internal fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(65_536)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it) }
    }

    internal data class UpdateInfo(
        val versionCode: Long,
        val url: String,
        val sha256: String
    )

    /** A download in progress, or a verified APK waiting to be installed. Survives process death. */
    private data class PendingDownload(
        val isSelfUpdate: Boolean,
        val downloadId: Long,                 // DownloadManager id; -1 once the APK is verified
        val expectedSha256: String? = null,
        val targetLabel: String? = null,
        val packageName: String? = null,      // of the verified APK
        val versionCode: Long = 0L,           // of the verified APK
        val installLaunched: Boolean = false,
        val installBaseline: Long = 0L        // target's lastUpdateTime when the installer was first opened
    ) {
        val fileName: String get() = if (isSelfUpdate) UPDATE_FILE_NAME else SETUP_FILE_NAME
        val isVerified: Boolean get() = downloadId < 0
    }

    private class DownloadStatus(
        val status: Int,
        val reason: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val downloadManager by lazy { getSystemService(DownloadManager::class.java) }
    private var updateCheckThread: Thread? = null
    private var updateDialog: AlertDialog? = null
    private var polling = false
    private var errorShown = false

    // Smoothed download speed for the stats tiles
    private var lastPollBytes = -1L
    private var lastPollTime = 0L
    private var speedBps = 0.0
    private var spaceCheckedFor = -1L

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDownload()
            if (polling) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = loadPending()
        if (pending != null && pending.isVerified && packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(apkFile(pending))
        }
    }

    // Header
    private lateinit var ivPhaseIcon: ImageView
    private lateinit var tvPhase: TextView
    private lateinit var mainStatusDot: View
    private lateinit var tvLive: TextView

    // Panels
    private lateinit var idlePanel: View
    private lateinit var progressPanel: View

    // Decorative ring views
    private lateinit var ivMainRingScanner: ImageView
    private lateinit var ivMainRingDashed: ImageView
    private lateinit var ivRingScanner: ImageView
    private lateinit var ivRingDashed: ImageView

    // Progress views
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var circular: CircularProgressIndicator
    private lateinit var tvPercent: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSize: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var btnInstall: MaterialButton
    private lateinit var btnCancel: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Decorative ring views
        ivMainRingScanner = findViewById(R.id.ivMainRingScanner)
        ivMainRingDashed  = findViewById(R.id.ivMainRingDashed)
        ivRingScanner     = findViewById(R.id.ivRingScanner)
        ivRingDashed      = findViewById(R.id.ivRingDashed)

        // Idle decorative animations
        ivMainRingScanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        ivMainRingDashed.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        mainStatusDot = findViewById(R.id.mainStatusDot)
        mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))

        // Header
        ivPhaseIcon = findViewById(R.id.ivPhaseIcon)
        tvPhase     = findViewById(R.id.tvPhase)
        tvLive      = findViewById(R.id.tvLive)
        tvPhase.text = getVersionChipText()

        // Panels
        idlePanel     = findViewById(R.id.idlePanel)
        progressPanel = findViewById(R.id.progressPanel)

        // Progress views
        tvStatus     = findViewById(R.id.tvStatus)
        tvStatusHint = findViewById(R.id.tvStatusHint)
        progressBar  = findViewById(R.id.progressBar)
        circular     = findViewById(R.id.circularProgress)
        tvPercent    = findViewById(R.id.tvPercent)
        tvSpeed      = findViewById(R.id.tvSpeed)
        tvSize       = findViewById(R.id.tvSize)
        tvRemaining  = findViewById(R.id.tvRemaining)
        btnInstall   = findViewById(R.id.btnInstall)
        btnCancel    = findViewById(R.id.btnCancel)

        circular.max = 1000

        findViewById<MaterialButton>(R.id.btnDownload).setOnClickListener {
            updateCheckThread?.interrupt()
            startDownload(APK_URL, isSelfUpdate = false)
        }

        btnInstall.setOnClickListener {
            loadPending()?.takeIf { it.isVerified }?.let { launchInstall(it) }
        }

        btnCancel.setOnClickListener {
            cancelPending()
            showIdlePanel()
        }

        cleanupStaleFiles()
        checkForSelfUpdate()
    }

    override fun onResume() {
        super.onResume()
        resumePending()
    }

    override fun onStop() {
        super.onStop()
        // The download itself keeps running in DownloadManager
        stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateDialog?.dismiss()
        updateDialog = null
        updateCheckThread?.interrupt()
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // Pending download state
    // ------------------------------------------------------------------

    private fun loadPending(): PendingDownload? {
        val prefs = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE)
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

    private fun savePending(pending: PendingDownload?) {
        // commit, not apply: the package installer may kill this process right after
        getSharedPreferences(PENDING_PREFS, MODE_PRIVATE).edit(commit = true) {
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

    private fun apkDir(): File = File(cacheDir, APK_DIR).apply { mkdirs() }

    private fun apkFile(pending: PendingDownload): File = File(apkDir(), pending.fileName)

    /** Deletes APKs and partial files from earlier runs that no pending download refers to. */
    private fun cleanupStaleFiles() {
        // A recreated activity: the copy in progress owns these files
        if (finalizingId >= 0) return

        val pending = loadPending()
        // Installer versions before 2.3 downloaded straight into cacheDir
        cacheDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".tmp")) }
            ?.forEach { it.delete() }
        apkDir().listFiles()
            ?.filter { pending == null || !pending.isVerified || it.name != pending.fileName }
            ?.forEach { it.delete() }
        // DownloadManager's files, unless it is still writing one
        if (pending == null || pending.isVerified) {
            getExternalFilesDir(null)?.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        }
    }

    /** Brings the UI in line with the saved state, e.g. after rotation or a process restart. */
    private fun resumePending() {
        val pending = loadPending()
        when {
            pending == null -> {
                val error = finalizeError
                if (error != null) {
                    finalizeError = null
                    showErrorState(error)
                } else if (!errorShown && progressPanel.isVisible) {
                    showIdlePanel()
                }
            }
            pending.isVerified -> onApkReady(pending)
            else -> {
                showDownloadingPanel(pending)
                startPolling()
            }
        }
    }

    private fun cancelPending() {
        stopPolling()
        finalizeError = null
        synchronized(finalizeLock) {
            val pending = loadPending() ?: return
            if (pending.downloadId >= 0) downloadManager.remove(pending.downloadId)
            apkFile(pending).delete()
            savePending(null)
        }
    }

    // ------------------------------------------------------------------
    // Self-update
    // ------------------------------------------------------------------

    private fun checkForSelfUpdate() {
        val localVersionCode = installedVersionCode()

        updateCheckThread = Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(SELF_UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout    = 10_000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                conn.connect()

                if (Thread.currentThread().isInterrupted) return@Thread
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Self-update manifest returned HTTP ${conn.responseCode}")
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (Thread.currentThread().isInterrupted) return@Thread

                val update = parseUpdateInfo(body)
                if (update == null) {
                    Log.d(TAG, "Self-update manifest is invalid")
                    return@Thread
                }
                if (update.versionCode <= localVersionCode) {
                    Log.d(TAG, "Installer is up to date (build $localVersionCode)")
                    return@Thread
                }

                Log.i(TAG, "Newer build ${update.versionCode} found (local=$localVersionCode): ${update.url}")
                handler.post {
                    if (!isFinishing && !isDestroyed &&
                        loadPending() == null &&
                        !progressPanel.isVisible &&
                        !isUpdateSnoozed(update.versionCode)
                    ) {
                        promptSelfUpdate(update)
                    }
                }
            } catch (_: InterruptedException) {
                // superseded by user action or lifecycle teardown
            } catch (e: Exception) {
                Log.d(TAG, "Self-update check skipped: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.also { it.start() }
    }

    private fun promptSelfUpdate(update: UpdateInfo) {
        val targetVersion = getString(R.string.build_format, update.versionCode)
        updateDialog?.dismiss()
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, targetVersion))
            .setPositiveButton(R.string.update_now) { _, _ ->
                startDownload(
                    url = update.url,
                    isSelfUpdate = true,
                    targetLabel = targetVersion,
                    expectedSha256 = update.sha256
                )
            }
            .setNegativeButton(R.string.update_later) { _, _ -> snoozeUpdate(update.versionCode) }
            .show()
    }

    private fun isUpdateSnoozed(versionCode: Long): Boolean {
        val prefs = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE)
        return isUpdateSnoozed(
            versionCode,
            prefs.getLong("snoozedVersionCode", 0L),
            prefs.getLong("snoozedAt", 0L),
            System.currentTimeMillis()
        )
    }

    private fun snoozeUpdate(versionCode: Long) {
        getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit {
            putLong("snoozedVersionCode", versionCode)
            putLong("snoozedAt", System.currentTimeMillis())
        }
    }

    private fun installedVersionCode(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    }.getOrDefault(0L)

    private fun installedSignerDigests(): Set<String> = runCatching {
        signerDigests(packageManager.getPackageInfo(packageName, SIGNATURE_FLAGS), includeHistory = false)
    }.getOrDefault(emptySet())

    /**
     * SHA-256 digests of the certificates [info] is signed with. With [includeHistory],
     * also the older certificates a rotated signing key has proven it replaces.
     */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo, includeHistory: Boolean): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            when {
                signingInfo == null -> info.signatures
                includeHistory && !signingInfo.hasMultipleSigners() -> signingInfo.signingCertificateHistory
                else -> signingInfo.apkContentsSigners
            }
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun getVersionChipText(): String {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        return if (!versionName.isNullOrBlank()) {
            getString(R.string.version_chip_format, versionName)
        } else {
            getString(R.string.main_chip_version)
        }
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private fun startDownload(
        url: String,
        isSelfUpdate: Boolean,
        targetLabel: String? = null,
        expectedSha256: String? = null
    ) {
        cancelPending()

        val fileName = if (isSelfUpdate) UPDATE_FILE_NAME else SETUP_FILE_NAME
        val downloadDir = getExternalFilesDir(null)
        if (downloadDir == null) {
            showErrorState(getString(R.string.error_storage_unavailable))
            return
        }
        // DownloadManager would otherwise save to "setup-1.apk"
        File(downloadDir, fileName).delete()

        val request = DownloadManager.Request(url.toUri())
            .addRequestHeader("User-Agent", USER_AGENT)
            .setTitle(getString(R.string.app_name))
            .setDescription(getString(if (isSelfUpdate) R.string.phase_updating else R.string.phase_downloading))
            // No "completed" notification: tapping it would open the APK without our checks
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(this, null, fileName)
        val downloadId = try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            // e.g. the system Download Manager app is disabled
            Log.e(TAG, "Could not start download", e)
            showErrorState(e.message)
            return
        }

        val pending = PendingDownload(isSelfUpdate, downloadId, expectedSha256, targetLabel)
        savePending(pending)
        showDownloadingPanel(pending)
        startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun queryDownload(downloadId: Long): DownloadStatus? {
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

    private fun pollDownload() {
        val pending = loadPending()
        if (pending == null) {
            stopPolling()
            val error = finalizeError
            if (error != null) {
                finalizeError = null
                showErrorState(error)
            }
            return
        }
        if (pending.isVerified) {
            stopPolling()
            onApkReady(pending)
            return
        }

        val status = queryDownload(pending.downloadId)
        when (status?.status) {
            null -> synchronized(finalizeLock) {
                // Removed outside the app (e.g. from the Downloads screen), unless we just finished it
                if (loadPending()?.downloadId == pending.downloadId) {
                    stopPolling()
                    savePending(null)
                    showErrorState(getString(R.string.error_download_removed))
                }
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                // Keep polling: the verified APK shows up in the saved state when done
                showVerifyingPanel()
                finalizeDownload(pending)
            }
            DownloadManager.STATUS_FAILED -> {
                stopPolling()
                downloadManager.remove(pending.downloadId)
                savePending(null)
                showErrorState(describeFailure(status.reason))
            }
            else -> showProgress(pending, status)
        }
    }

    private fun describeFailure(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> getString(R.string.error_no_space_generic)
        DownloadManager.ERROR_DEVICE_NOT_FOUND   -> getString(R.string.error_storage_unavailable)
        in 400..599                              -> getString(R.string.error_http, reason)
        else                                     -> getString(R.string.error_download_failed, reason)
    }

    /** Copies the finished download into cacheDir/apk and verifies it, off the main thread. */
    private fun finalizeDownload(pending: PendingDownload) {
        synchronized(finalizeLock) {
            if (finalizingId == pending.downloadId) return
            finalizingId = pending.downloadId
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
                val archive = verifyApk(partial, pending)

                synchronized(finalizeLock) {
                    // Cancelled while we were copying
                    if (loadPending()?.downloadId != pending.downloadId) return@Thread
                    if (!partial.renameTo(target)) throw IOException("Could not move the APK into place")
                    savePending(
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
                synchronized(finalizeLock) {
                    if (loadPending()?.downloadId == pending.downloadId) {
                        savePending(null)
                        downloadManager.remove(pending.downloadId)
                        finalizeError = e.message ?: getString(R.string.error_unknown)
                    }
                }
            } finally {
                partial.delete()
                finalizingId = -1L
            }
        }.start()
    }

    /** Checks [file] is an intact APK from the expected publisher and returns its package info. */
    private fun verifyApk(file: File, pending: PendingDownload): PackageInfo {
        // Validate ZIP/APK magic: PK\x03\x04
        val magic = ByteArray(4)
        val magicRead = file.inputStream().use { it.read(magic) }
        if (magicRead < 4 ||
            magic[0] != 0x50.toByte() ||
            magic[1] != 0x4B.toByte() ||
            magic[2] != 0x03.toByte() ||
            magic[3] != 0x04.toByte()
        ) {
            val hex = if (magicRead > 0) {
                magic.take(magicRead).joinToString(" ") { "%02X".format(it) }
            } else {
                "empty"
            }
            Log.e(TAG, "Not a valid APK: $hex")
            throw Exception("Downloaded file is not a valid APK ($hex)")
        }

        if (pending.expectedSha256 != null) {
            val actualSha256 = sha256Hex(file)
            if (!actualSha256.equals(pending.expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "SHA-256 mismatch: expected ${pending.expectedSha256}, got $actualSha256")
                throw Exception("Downloaded APK checksum does not match")
            }
        }

        // Also verifies the APK signature; a tampered archive comes back null
        val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, SIGNATURE_FLAGS)
        if (archiveInfo == null) {
            Log.e(TAG, "PackageArchiveInfo check failed for ${file.absolutePath}")
            throw Exception("Downloaded APK archive is corrupted")
        }
        val archiveSigners = signerDigests(archiveInfo, includeHistory = true)

        if (pending.isSelfUpdate) {
            // Only ever install a newer build of ourselves, so a stale release
            // asset can never put the self-update into a reinstall loop.
            val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
            val localVersionCode = installedVersionCode()
            if (archiveInfo.packageName != packageName || archiveVersionCode <= localVersionCode) {
                Log.e(
                    TAG,
                    "Rejected update ${archiveInfo.packageName} build $archiveVersionCode " +
                        "(installed build $localVersionCode)"
                )
                throw Exception("Downloaded update is not newer than the installed version")
            }
            if (!isSignedByAny(archiveSigners, installedSignerDigests())) {
                Log.e(TAG, "Rejected update signed by $archiveSigners")
                throw Exception("Downloaded update is not signed with the installer's key")
            }
        } else if (archiveInfo.packageName !in SETUP_PACKAGE_NAMES ||
            !isSignedByAny(archiveSigners, SETUP_SIGNER_SHA256)
        ) {
            Log.e(TAG, "Rejected ${archiveInfo.packageName} signed by $archiveSigners")
            throw Exception("Downloaded APK is not signed by the KaliDroid publisher")
        }
        return archiveInfo
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    private fun onApkReady(pending: PendingDownload) {
        val apk = apkFile(pending)
        // Purged from cache by the system, or the user already installed it
        if (!apk.exists() || (pending.installLaunched && isInstalled(pending))) {
            apk.delete()
            savePending(null)
            showIdlePanel()
            return
        }

        showReadyPanel(apk.length())
        // Only hand off while we're in front; otherwise wait for onResume
        if (!pending.installLaunched && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            launchInstall(pending)
        }
    }

    /**
     * True once the APK has actually been installed since we opened the installer. The
     * same version may already have been installed before, so the version alone isn't proof.
     */
    private fun isInstalled(pending: PendingDownload): Boolean {
        val info = installedPackage(pending) ?: return false
        return PackageInfoCompat.getLongVersionCode(info) >= pending.versionCode &&
            info.lastUpdateTime > pending.installBaseline
    }

    private fun installedPackage(pending: PendingDownload): PackageInfo? {
        val pkg = pending.packageName ?: return null
        return runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull()
    }

    private fun launchInstall(pending: PendingDownload) {
        val baseline = if (pending.installLaunched) {
            pending.installBaseline
        } else {
            installedPackage(pending)?.lastUpdateTime ?: 0L
        }
        savePending(pending.copy(installLaunched = true, installBaseline = baseline))
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text      = getString(R.string.phase_installing)
        tvStatus.text     = getString(R.string.status_installing)
        tvStatusHint.text = getString(R.string.status_hint_install)
        installApk(apkFile(pending))
    }

    private fun installApk(file: File) {
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.fromParts("package", packageName, null)
                )
                unknownSourcesLauncher.launch(settingsIntent)
                return
            }
            launchPackageInstaller(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate APK install", e)
            showErrorState(e.message)
        }
    }

    private fun launchPackageInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            showErrorState(e.message)
        }
    }

    // ------------------------------------------------------------------
    // UI states
    // ------------------------------------------------------------------

    private fun showIdlePanel() {
        errorShown = false
        ivRingScanner.clearAnimation()
        ivRingDashed.clearAnimation()
        progressPanel.visibility = View.GONE
        idlePanel.visibility = View.VISIBLE
        if (ivMainRingScanner.animation == null) {
            ivMainRingScanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
            ivMainRingDashed.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        }
        // Reset header back to its ready/online state
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text = getVersionChipText()
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        tvLive.text = getString(R.string.main_ready_label)
        tvLive.setTextColor(getColor(R.color.valid_green))
        resetStatusDot()
        btnInstall.visibility = View.GONE
        btnCancel.text = getString(android.R.string.cancel)
    }

    /** Swaps to the progress panel with the live header; shared by every non-idle state. */
    private fun showProgressPanel() {
        errorShown = false
        ivMainRingScanner.clearAnimation()
        ivMainRingDashed.clearAnimation()
        idlePanel.visibility = View.GONE
        progressPanel.visibility = View.VISIBLE
        if (ivRingScanner.animation == null) {
            ivRingScanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
            ivRingDashed.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        }
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        tvLive.text = getString(R.string.live_label)
        tvLive.setTextColor(getColor(R.color.valid_green))
        resetStatusDot()
        btnInstall.visibility = View.GONE
        btnCancel.text = getString(android.R.string.cancel)
    }

    private fun resetStatusDot() {
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = null
        mainStatusDot.alpha = 1f
        if (mainStatusDot.animation == null) {
            mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))
        }
    }

    private fun showDownloadingPanel(pending: PendingDownload) {
        showProgressPanel()

        progressBar.isIndeterminate = false
        circular.isIndeterminate = false
        circular.setProgressCompat(0, false)
        progressBar.progress = 0
        tvPercent.text   = getString(R.string.percent_format, 0)
        tvSpeed.text     = getString(R.string.dash)
        tvSize.text      = getString(R.string.dash)
        tvRemaining.text = getString(R.string.dash)

        ivPhaseIcon.setImageResource(R.drawable.ic_download)
        tvPhase.text = getString(
            if (pending.isSelfUpdate) R.string.phase_updating else R.string.phase_downloading
        )
        tvStatus.text = if (pending.isSelfUpdate && !pending.targetLabel.isNullOrBlank()) {
            getString(R.string.status_updating, pending.targetLabel)
        } else {
            getString(R.string.status_connecting)
        }
        tvStatusHint.text = getString(
            if (pending.isSelfUpdate) R.string.status_hint_update else R.string.status_hint_connect
        )

        lastPollBytes = -1L
        speedBps = 0.0
    }

    private fun showProgress(pending: PendingDownload, status: DownloadStatus) {
        val total = status.totalBytes
        val downloaded = status.downloadedBytes

        // Fail early instead of after hundreds of MB
        if (total > 0 && spaceCheckedFor != pending.downloadId) {
            spaceCheckedFor = pending.downloadId
            val needed = bytesNeeded(total, downloaded)
            if (allocatableBytes() < needed) {
                cancelPending()
                showErrorState(getString(R.string.error_no_space, formatBytes(needed)))
                return
            }
        }

        val running = status.status == DownloadManager.STATUS_RUNNING
        val now = SystemClock.elapsedRealtime()
        if (!running) {
            speedBps = 0.0
        } else if (lastPollBytes in 0..downloaded && now > lastPollTime) {
            val instant = (downloaded - lastPollBytes) * 1000.0 / (now - lastPollTime)
            speedBps = if (speedBps <= 0.0) instant else speedBps * 0.7 + instant * 0.3
        }
        lastPollBytes = downloaded
        lastPollTime = now

        if (total > 0) {
            val permille = (downloaded * 1000 / total).toInt().coerceIn(0, 999)
            progressBar.isIndeterminate = false
            circular.isIndeterminate = false
            progressBar.progress = permille
            circular.setProgressCompat(permille, true)
            tvPercent.text = getString(R.string.percent_format, permille / 10)
            tvSize.text = getString(R.string.size_format, formatBytes(downloaded), formatBytes(total))
        } else {
            progressBar.isIndeterminate = true
            tvPercent.text = getString(R.string.dash)
            tvSize.text = formatBytes(downloaded)
        }

        val paused = status.status == DownloadManager.STATUS_PAUSED
        tvStatus.text = when {
            paused -> getString(R.string.status_waiting)
            pending.isSelfUpdate && !pending.targetLabel.isNullOrBlank() ->
                getString(R.string.status_updating, pending.targetLabel)
            total <= 0 -> getString(R.string.status_connecting)
            else -> getString(R.string.status_downloading)
        }
        tvStatusHint.text = getString(
            when {
                paused -> R.string.status_hint_waiting
                pending.isSelfUpdate -> R.string.status_hint_update
                total <= 0 -> R.string.status_hint_connect
                else -> R.string.status_hint_download
            }
        )
        tvSpeed.text = if (running) {
            getString(R.string.speed_format, speedBps / (1024.0 * 1024.0))
        } else {
            getString(R.string.dash)
        }
        tvRemaining.text = if (running && total > 0 && speedBps > 0) {
            formatRemaining(((total - downloaded) / speedBps).toLong())
        } else {
            formatRemaining(-1)
        }
    }

    /** Free space on the internal volume, counting cache the system would clear for us. */
    private fun allocatableBytes(): Long = runCatching {
        val storage = getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(cacheDir))
    }.getOrElse { cacheDir.usableSpace }

    private fun showVerifyingPanel() {
        showProgressPanel()
        progressBar.isIndeterminate = false
        progressBar.progress = 1000
        circular.setProgressCompat(1000, false)
        tvPercent.text    = getString(R.string.percent_format, 100)
        tvSpeed.text      = getString(R.string.dash)
        tvRemaining.text  = getString(R.string.dash)
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text      = getString(R.string.phase_verifying)
        tvStatus.text     = getString(R.string.status_verifying)
        tvStatusHint.text = getString(R.string.status_hint_verify)
    }

    private fun showReadyPanel(apkBytes: Long) {
        showVerifyingPanel()
        ivRingScanner.clearAnimation()
        ivRingDashed.clearAnimation()
        tvSize.text       = getString(R.string.size_format, formatBytes(apkBytes), formatBytes(apkBytes))
        tvPhase.text      = getString(R.string.phase_ready)
        tvStatus.text     = getString(R.string.status_ready)
        tvStatusHint.text = getString(R.string.status_hint_ready)
        btnInstall.visibility = View.VISIBLE
    }

    private fun showErrorState(message: String?) {
        showProgressPanel()
        errorShown = true
        ivRingScanner.clearAnimation()
        ivRingDashed.clearAnimation()
        mainStatusDot.clearAnimation()
        mainStatusDot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.invalid_red))
        tvLive.text = getString(R.string.offline_label)
        tvLive.setTextColor(getColor(R.color.invalid_red))
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text       = getString(R.string.phase_error)
        tvPhase.setTextColor(getColor(R.color.invalid_red))
        tvStatus.text      = message ?: getString(R.string.error_unknown)
        tvStatusHint.text  = getString(R.string.status_hint_error)
        tvSpeed.text       = getString(R.string.dash)
        tvRemaining.text   = getString(R.string.dash)
        btnCancel.text     = getString(android.R.string.ok)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.size - 1) {
            value /= 1024.0
            unitIdx++
        }
        return if (unitIdx == 0) {
            String.format(Locale.US, "%d %s", value.toLong(), units[unitIdx])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIdx])
        }
    }

    private fun formatRemaining(seconds: Long): String = when {
        seconds < 0   -> getString(R.string.remaining_calculating)
        seconds == 0L -> getString(R.string.dash)
        seconds < 60  -> getString(R.string.remaining_seconds, seconds)
        else          -> getString(R.string.remaining_minutes, seconds / 60, seconds % 60)
    }
}
