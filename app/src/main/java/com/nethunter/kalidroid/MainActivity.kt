package com.nethunter.kalidroid

import android.animation.ValueAnimator
import android.app.DownloadManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AnimRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val APK_URL = "https://kalidroid.com/setup.apk"
        private const val FILE_PROVIDER_AUTHORITY = "com.nethunter.kalidroid.provider"
        private const val POLL_INTERVAL_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val downloads by lazy { ApkDownloads(this) }
    private val selfUpdate by lazy { SelfUpdate(this) }
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
        val pending = downloads.load()
        if (pending != null && pending.isVerified && packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(downloads.apkFile(pending))
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
        // Light bar icons over our dark background, on every Android version
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep the card clear of the status bar, navigation bar and display cutouts. The space
        // above and below the card is padding here rather than card margin, because ScrollView
        // leaves a child's vertical margins out of how far it lets you scroll.
        val cardSpacing = resources.getDimensionPixelSize(R.dimen.card_spacing)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top + cardSpacing,
                right = bars.right,
                bottom = bars.bottom + cardSpacing
            )
            insets
        }

        // Decorative ring views
        ivMainRingScanner = findViewById(R.id.ivMainRingScanner)
        ivMainRingDashed  = findViewById(R.id.ivMainRingDashed)
        ivRingScanner     = findViewById(R.id.ivRingScanner)
        ivRingDashed      = findViewById(R.id.ivRingDashed)

        // Idle decorative animations
        animate(ivMainRingScanner, R.anim.rotate_cw)
        animate(ivMainRingDashed, R.anim.rotate_ccw)
        mainStatusDot = findViewById(R.id.mainStatusDot)
        animate(mainStatusDot, R.anim.blink)

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
        tvPercent.text = getString(R.string.percent_format, 0)

        findViewById<MaterialButton>(R.id.btnDownload).setOnClickListener {
            updateCheckThread?.interrupt()
            startDownload(APK_URL, isSelfUpdate = false)
        }

        btnInstall.setOnClickListener {
            downloads.load()?.takeIf { it.isVerified }?.let { launchInstall(it) }
        }

        btnCancel.setOnClickListener {
            stopPolling()
            downloads.cancel()
            showIdlePanel()
        }

        downloads.cleanupStaleFiles()
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

    /** Brings the UI in line with the saved state, e.g. after rotation or a process restart. */
    private fun resumePending() {
        val pending = downloads.load()
        when {
            pending == null -> {
                val error = downloads.takeFinishError()
                if (error != null) {
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

    // ------------------------------------------------------------------
    // Self-update
    // ------------------------------------------------------------------

    private fun checkForSelfUpdate() {
        val localVersionCode = installedVersionCode()

        updateCheckThread = Thread {
            val update = selfUpdate.fetchNewerBuild(localVersionCode)
            // Interrupted: superseded by user action or lifecycle teardown
            if (update == null || Thread.currentThread().isInterrupted) return@Thread
            handler.post {
                if (!isFinishing && !isDestroyed &&
                    downloads.load() == null &&
                    !progressPanel.isVisible &&
                    !selfUpdate.isSnoozed(update.versionCode)
                ) {
                    promptSelfUpdate(update)
                }
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
            .setNegativeButton(R.string.update_later) { _, _ -> selfUpdate.snooze(update.versionCode) }
            .show()
    }

    private fun getVersionChipText(): String =
        getString(R.string.version_chip_format, installedPackage()?.versionName.orEmpty())

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    private fun startDownload(
        url: String,
        isSelfUpdate: Boolean,
        targetLabel: String? = null,
        expectedSha256: String? = null
    ) {
        stopPolling()
        val pending = try {
            downloads.start(url, isSelfUpdate, targetLabel, expectedSha256)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start download", e)
            showErrorState(e.message)
            return
        }
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

    private fun pollDownload() {
        val pending = downloads.load()
        if (pending == null) {
            stopPolling()
            downloads.takeFinishError()?.let { showErrorState(it) }
            return
        }
        if (pending.isVerified) {
            stopPolling()
            onApkReady(pending)
            return
        }

        val status = downloads.query(pending.downloadId)
        when (status?.status) {
            null -> if (downloads.forgetMissing(pending)) {
                stopPolling()
                showErrorState(getString(R.string.error_download_removed))
            }
            DownloadManager.STATUS_SUCCESSFUL -> {
                // Keep polling: the verified APK shows up in the saved state when done
                showVerifyingPanel()
                downloads.finish(pending)
            }
            DownloadManager.STATUS_FAILED -> {
                stopPolling()
                downloads.discardFailed(pending)
                showErrorState(downloads.failureMessage(status.reason))
            }
            else -> showProgress(pending, status)
        }
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    private fun onApkReady(pending: PendingDownload) {
        val apk = downloads.apkFile(pending)
        // Purged from cache by the system, or the user already installed it
        if (!apk.exists() || (pending.installLaunched && downloads.isInstalled(pending))) {
            downloads.discardApk(pending)
            showIdlePanel()
            return
        }

        showReadyPanel(apk.length())
        // Only hand off while we're in front; otherwise wait for onResume
        if (!pending.installLaunched && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            launchInstall(pending)
        }
    }

    private fun launchInstall(pending: PendingDownload) {
        val launched = downloads.markInstallLaunched(pending)
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text      = getString(R.string.phase_installing)
        tvStatus.text     = getString(R.string.status_installing)
        tvStatusHint.text = getString(R.string.status_hint_install)
        installApk(downloads.apkFile(launched))
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

    /** Starts a looping decorative animation, unless one runs already or animations are turned off. */
    private fun animate(view: View, @AnimRes anim: Int) {
        if (view.animation == null && ValueAnimator.areAnimatorsEnabled()) {
            view.startAnimation(AnimationUtils.loadAnimation(this, anim))
        }
    }

    private fun showIdlePanel() {
        errorShown = false
        ivRingScanner.clearAnimation()
        ivRingDashed.clearAnimation()
        progressPanel.visibility = View.GONE
        idlePanel.visibility = View.VISIBLE
        animate(ivMainRingScanner, R.anim.rotate_cw)
        animate(ivMainRingDashed, R.anim.rotate_ccw)
        // Reset header back to its ready/online state
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text = getVersionChipText()
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        setLiveIndicator(R.string.main_ready_label, R.color.valid_green)
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
        animate(ivRingScanner, R.anim.rotate_cw)
        animate(ivRingDashed, R.anim.rotate_ccw)
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        setLiveIndicator(R.string.live_label, R.color.valid_green)
        btnInstall.visibility = View.GONE
        btnCancel.text = getString(android.R.string.cancel)
    }

    private fun setLiveIndicator(labelRes: Int, colorRes: Int) {
        tvLive.text = getString(labelRes)
        tvLive.setTextColor(getColor(colorRes))
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
        mainStatusDot.alpha = 1f
        animate(mainStatusDot, R.anim.blink)
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
            val needed = downloads.spaceShortfall(status)
            if (needed != null) {
                stopPolling()
                downloads.cancel()
                showErrorState(getString(R.string.error_no_space, formatBytes(needed)))
                return
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (!status.isRunning) {
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

        if (status.isPaused) {
            setLiveIndicator(R.string.offline_label, R.color.text_secondary)
        } else {
            setLiveIndicator(R.string.live_label, R.color.valid_green)
        }
        tvStatus.text = when {
            status.isPaused -> getString(R.string.status_waiting)
            pending.isSelfUpdate && !pending.targetLabel.isNullOrBlank() ->
                getString(R.string.status_updating, pending.targetLabel)
            total <= 0 -> getString(R.string.status_connecting)
            else -> getString(R.string.status_downloading)
        }
        tvStatusHint.text = getString(
            when {
                status.isPaused -> R.string.status_hint_waiting
                pending.isSelfUpdate -> R.string.status_hint_update
                total <= 0 -> R.string.status_hint_connect
                else -> R.string.status_hint_download
            }
        )
        tvSpeed.text = if (status.isRunning) {
            getString(R.string.speed_format, speedBps / (1024.0 * 1024.0))
        } else {
            getString(R.string.dash)
        }
        tvRemaining.text = if (status.isRunning && total > 0 && speedBps > 0) {
            formatRemaining(((total - downloaded) / speedBps).toLong())
        } else {
            formatRemaining(-1)
        }
    }

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
        setLiveIndicator(R.string.failed_label, R.color.invalid_red)
        mainStatusDot.clearAnimation()
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text       = getString(R.string.phase_error)
        tvPhase.setTextColor(getColor(R.color.invalid_red))
        tvStatus.text      = message ?: getString(R.string.error_unknown)
        tvStatusHint.text  = getString(R.string.status_hint_error)
        tvSpeed.text       = getString(R.string.dash)
        tvRemaining.text   = getString(R.string.dash)
        btnCancel.text     = getString(android.R.string.ok)
    }

    // Same number formatting as the speed and percent strings: the user's locale
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
            String.format(Locale.getDefault(), "%d %s", value.toLong(), units[unitIdx])
        } else {
            String.format(Locale.getDefault(), "%.1f %s", value, units[unitIdx])
        }
    }

    private fun formatRemaining(seconds: Long): String = when {
        seconds < 0   -> getString(R.string.remaining_calculating)
        seconds == 0L -> getString(R.string.dash)
        seconds < 60  -> getString(R.string.remaining_seconds, seconds)
        else          -> getString(R.string.remaining_minutes, seconds / 60, seconds % 60)
    }
}
