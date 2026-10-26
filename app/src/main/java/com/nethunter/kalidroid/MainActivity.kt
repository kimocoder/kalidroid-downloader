package com.nethunter.kalidroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KaliDroid"
        private const val APK_URL = "https://kalidroid.com/setup.apk"
        private const val GITHUB_LATEST_COMMIT_URL =
            "https://api.github.com/repos/kimocoder/kalidroid-downloader/commits/master"
        private const val GITHUB_RELEASES_URL =
            "https://api.github.com/repos/kimocoder/kalidroid-downloader/releases?per_page=5"
        private const val SELF_UPDATE_APK_FALLBACK_URL =
            "https://github.com/kimocoder/kalidroid-downloader/releases/download/latest/kalidroid.apk"
        private const val USER_AGENT = "KaliDroid-Installer"
        private const val FILE_PROVIDER_AUTHORITY = "com.nethunter.kalidroid.provider"

        internal fun isNewerCommit(remoteSha: String, localSha: String): Boolean {
            val remote = remoteSha.trim()
            val local = localSha.trim()
            fun isHexSha(s: String): Boolean =
                s.length >= 7 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

            if (!isHexSha(remote) || !isHexSha(local)) return false
            return !remote.startsWith(local, ignoreCase = true) &&
                !local.startsWith(remote, ignoreCase = true)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downloadThread: Thread? = null
    private var updateCheckThread: Thread? = null
    @Volatile
    private var activeConnection: HttpURLConnection? = null
    @Volatile
    private var downloadGeneration = 0
    private var pendingApkFile: File? = null

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingApkFile
        pendingApkFile = null
        if (file != null && file.exists() && packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(file)
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
        btnCancel    = findViewById(R.id.btnCancel)

        circular.max = 1000

        findViewById<MaterialButton>(R.id.btnDownload).setOnClickListener {
            updateCheckThread?.interrupt()
            startDownload()
        }

        btnCancel.setOnClickListener {
            cancelActiveDownload()
            showIdlePanel()
        }

        checkForSelfUpdate()
    }

    private fun cancelActiveDownload() {
        downloadGeneration++
        handler.removeCallbacksAndMessages(null)
        downloadThread?.interrupt()
        downloadThread = null
        val conn = activeConnection
        activeConnection = null
        if (conn != null) {
            Thread {
                runCatching { conn.disconnect() }
            }.start()
        }
    }

    private fun postForDownload(generation: Int, action: () -> Unit) {
        handler.post {
            if (downloadGeneration == generation && !isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun checkForSelfUpdate() {
        val localSha = getString(R.string.git_commit_sha).trim()
        if (localSha.isBlank()) return

        updateCheckThread = Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(GITHUB_LATEST_COMMIT_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout    = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                conn.connect()

                if (Thread.currentThread().isInterrupted) return@Thread
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "Self-update commit check returned HTTP ${conn.responseCode}")
                    return@Thread
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                if (Thread.currentThread().isInterrupted) return@Thread

                val commitJson = JSONObject(body)
                val remoteSha = commitJson.optString("sha", "").trim()
                if (!isNewerCommit(remoteSha, localSha)) {
                    Log.d(TAG, "Installer commit is up to date (sha=${localSha.take(7)})")
                    return@Thread
                }

                val updateUrl = resolveSelfUpdateApkUrl()
                if (Thread.currentThread().isInterrupted) return@Thread

                val shortSha = remoteSha.take(7)
                Log.i(TAG, "Newer commit $shortSha found (local=${localSha.take(7)}): $updateUrl")
                handler.post {
                    if (!isFinishing && !isDestroyed &&
                        downloadThread?.isAlive != true &&
                        progressPanel.visibility != View.VISIBLE
                    ) {
                        startDownload(
                            url = updateUrl,
                            outFileName = "kalidroid-update.apk",
                            isSelfUpdate = true,
                            targetVersion = shortSha
                        )
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

    private fun resolveSelfUpdateApkUrl(): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(GITHUB_RELEASES_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (r in 0 until releases.length()) {
                    val release = releases.optJSONObject(r) ?: continue
                    val assets = release.optJSONArray("assets") ?: continue
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val contentType = asset.optString("content_type", "")
                        if (name.endsWith(".apk", ignoreCase = true) ||
                            contentType.equals("application/vnd.android.package-archive", ignoreCase = true)
                        ) {
                            val url = asset.optString("browser_download_url", "")
                            if (url.isNotBlank()) return url
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Release asset lookup fell back to default URL: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return SELF_UPDATE_APK_FALLBACK_URL
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

    private fun showIdlePanel() {
        ivRingScanner.clearAnimation()
        ivRingDashed.clearAnimation()
        progressPanel.visibility = View.GONE
        idlePanel.visibility = View.VISIBLE
        ivMainRingScanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        ivMainRingDashed.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        // Reset header back to its ready/online state
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text = getVersionChipText()
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        tvLive.text = getString(R.string.main_ready_label)
        tvLive.setTextColor(getColor(R.color.valid_green))
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = null
        mainStatusDot.alpha = 1f
        mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))
        btnCancel.text = getString(android.R.string.cancel)
    }

    private fun startDownload(
        url: String = APK_URL,
        outFileName: String = "setup.apk",
        isSelfUpdate: Boolean = false,
        targetVersion: String? = null
    ) {
        cancelActiveDownload()
        val generation = ++downloadGeneration

        // Swap to the progress panel
        ivMainRingScanner.clearAnimation()
        ivMainRingDashed.clearAnimation()
        idlePanel.visibility = View.GONE
        progressPanel.visibility = View.VISIBLE

        // Decorative animations for the progress rings + dot
        ivRingScanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        ivRingDashed.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))

        // Reset state
        progressBar.isIndeterminate = false
        circular.isIndeterminate = false
        circular.setProgressCompat(0, false)
        progressBar.progress = 0
        tvPercent.text   = getString(R.string.percent_format, 0)
        tvSpeed.text     = getString(R.string.dash)
        tvSize.text      = getString(R.string.dash)
        tvRemaining.text = getString(R.string.dash)
        btnCancel.text   = getString(android.R.string.cancel)

        // Header → downloading / updating state
        ivPhaseIcon.setImageResource(R.drawable.ic_download)
        tvPhase.text  = getString(
            if (isSelfUpdate) R.string.phase_updating else R.string.phase_downloading
        )
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        tvLive.text   = getString(R.string.live_label)
        tvLive.setTextColor(getColor(R.color.valid_green))
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = null
        mainStatusDot.alpha = 1f
        mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))

        tvStatus.text = if (isSelfUpdate && !targetVersion.isNullOrBlank()) {
            getString(R.string.status_updating, targetVersion)
        } else {
            getString(R.string.status_connecting)
        }
        tvStatusHint.text = getString(
            if (isSelfUpdate) R.string.status_hint_update else R.string.status_hint_connect
        )

        downloadThread = Thread {
            var downloadedFile: File? = null
            try {
                val apkFile = downloadApk(
                    downloadUrl = url,
                    outFileName = outFileName,
                    onProgress = { percent, downloadedBytes, totalBytes, speedMbps, remainingSecs ->
                        postForDownload(generation) {
                            if (percent >= 0) {
                                progressBar.isIndeterminate = false
                                circular.isIndeterminate = false
                                val scaled = (percent * 10).coerceIn(0, 1000)
                                progressBar.progress = scaled
                                circular.setProgressCompat(scaled, true)
                                tvPercent.text = getString(R.string.percent_format, percent)
                            } else {
                                progressBar.isIndeterminate = true
                                tvPercent.text = getString(R.string.dash)
                            }
                            tvSpeed.text = getString(R.string.speed_format, speedMbps)
                            tvSize.text = if (totalBytes > 0) {
                                getString(
                                    R.string.size_format,
                                    formatBytes(downloadedBytes),
                                    formatBytes(totalBytes)
                                )
                            } else {
                                formatBytes(downloadedBytes)
                            }
                            tvRemaining.text = formatRemaining(remainingSecs)
                        }
                    },
                    onConnected = { totalBytes ->
                        postForDownload(generation) {
                            tvStatus.text = if (isSelfUpdate && !targetVersion.isNullOrBlank()) {
                                getString(R.string.status_updating, targetVersion)
                            } else {
                                getString(R.string.status_downloading)
                            }
                            tvStatusHint.text = getString(
                                if (isSelfUpdate) R.string.status_hint_update else R.string.status_hint_download
                            )
                            tvSize.text = if (totalBytes > 0) {
                                getString(
                                    R.string.size_format,
                                    formatBytes(0L),
                                    formatBytes(totalBytes)
                                )
                            } else {
                                formatBytes(0L)
                            }
                        }
                    }
                )
                downloadedFile = apkFile

                Log.d(TAG, "Downloaded: ${apkFile.absolutePath} (${apkFile.length()} B)")

                postForDownload(generation) {
                    ivPhaseIcon.setImageResource(R.drawable.ic_package)
                    tvPhase.text      = getString(R.string.phase_verifying)
                    tvStatus.text     = getString(R.string.status_verifying)
                    tvStatusHint.text = getString(R.string.status_hint_verify)
                }

                // Validate ZIP/APK magic: PK\x03\x04
                val magic = ByteArray(4)
                val magicRead = apkFile.inputStream().use { it.read(magic) }
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

                if (packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) == null) {
                    Log.e(TAG, "PackageArchiveInfo check failed for ${apkFile.absolutePath}")
                    throw Exception("Downloaded APK archive is corrupted")
                }

                if (downloadGeneration != generation || Thread.currentThread().isInterrupted) {
                    throw InterruptedException()
                }

                postForDownload(generation) {
                    progressBar.isIndeterminate = false
                    circular.isIndeterminate = false
                    progressBar.progress = 1000
                    circular.setProgressCompat(1000, true)
                    tvPercent.text  = getString(R.string.percent_format, 100)
                    tvSpeed.text    = getString(R.string.dash)
                    tvSize.text     = getString(
                        R.string.size_format,
                        formatBytes(apkFile.length()),
                        formatBytes(apkFile.length())
                    )
                    tvRemaining.text = getString(R.string.dash)
                    tvPhase.text    = getString(R.string.phase_installing)
                    tvStatus.text   = getString(R.string.status_installing)
                    tvStatusHint.text = getString(R.string.status_hint_install)
                }

                Thread.sleep(700)
                if (downloadGeneration != generation || Thread.currentThread().isInterrupted) {
                    throw InterruptedException()
                }
                postForDownload(generation) {
                    showIdlePanel()
                    installApk(apkFile)
                }

            } catch (_: InterruptedException) {
                downloadedFile?.delete()
            } catch (e: Exception) {
                downloadedFile?.delete()
                if (downloadGeneration != generation || Thread.currentThread().isInterrupted) return@Thread
                postForDownload(generation) {
                    showErrorState(e.message)
                }
            }
        }
        downloadThread?.start()
    }

    private fun showErrorState(message: String?) {
        ivMainRingScanner.clearAnimation()
        ivMainRingDashed.clearAnimation()
        idlePanel.visibility = View.GONE
        progressPanel.visibility = View.VISIBLE
        ivRingScanner.clearAnimation()
        ivRingDashed.clearAnimation()
        mainStatusDot.clearAnimation()
        mainStatusDot.alpha = 1f
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(R.color.invalid_red)
        )
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

    private fun downloadApk(
        downloadUrl: String,
        outFileName: String,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speedMbps: Double, remainingSecs: Long) -> Unit,
        onConnected: (totalBytes: Long) -> Unit
    ): File {
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        activeConnection = conn
        val tempFile = File.createTempFile("$outFileName.", ".tmp", cacheDir)
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout    = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()

            if (Thread.currentThread().isInterrupted) throw InterruptedException()

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Server returned HTTP $responseCode")
            }

            val contentLength = conn.contentLengthLong
            onConnected(contentLength)

            conn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buf = ByteArray(65_536)
                    var downloaded = 0L
                    var read: Int
                    val startTime = System.currentTimeMillis()
                    var lastTime  = startTime
                    var lastBytes = 0L

                    while (input.read(buf).also { read = it } != -1) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        output.write(buf, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 250) {
                            val intervalBytes = downloaded - lastBytes
                            val intervalMs = (now - lastTime).coerceAtLeast(1)
                            val speedBps = intervalBytes * 1000.0 / intervalMs
                            val speedMbps = speedBps / (1024.0 * 1024.0)

                            val percent = if (contentLength > 0) {
                                (downloaded * 100 / contentLength).toInt().coerceIn(0, 99)
                            } else {
                                -1
                            }
                            val remaining = if (contentLength > 0 && speedBps > 0)
                                ((contentLength - downloaded) / speedBps).toLong() else -1L

                            onProgress(percent, downloaded, contentLength, speedMbps, remaining)
                            lastTime  = now
                            lastBytes = downloaded
                        }
                    }

                    if (contentLength > 0 && downloaded != contentLength) {
                        throw Exception(
                            "Download incomplete (${formatBytes(downloaded)} / ${formatBytes(contentLength)})"
                        )
                    }
                }
            }

            if (Thread.currentThread().isInterrupted) throw InterruptedException()

            val outFile = File(cacheDir, outFileName)
            if (outFile.exists()) outFile.delete()
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            return outFile
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (activeConnection === conn) {
                activeConnection = null
            }
            runCatching { conn.disconnect() }
        }
    }

    private fun installApk(file: File) {
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingApkFile = file
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

    override fun onDestroy() {
        super.onDestroy()
        updateCheckThread?.interrupt()
        cancelActiveDownload()
    }
}
