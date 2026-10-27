package com.nethunter.kalidroid

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A newer installer build, as announced by the version.json CI publishes. */
internal data class UpdateInfo(
    val versionCode: Long,
    val url: String,
    val sha256: String
)

/** Looks for newer installer builds, and remembers when the user put one off. */
internal class SelfUpdate(context: Context) {

    companion object {
        // Published by CI next to the rolling APK: {"versionCode", "url", "sha256", ...}
        private const val MANIFEST_URL =
            "https://github.com/kimocoder/kalidroid-downloader/releases/download/latest/version.json"
        private const val PREFS = "self_update"
        private const val SNOOZE_MS = 24 * 60 * 60 * 1000L

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

        /** "Later" on the update prompt hides that build (and older ones) for a day. */
        internal fun isSnoozed(
            versionCode: Long,
            snoozedVersionCode: Long,
            snoozedAtMs: Long,
            nowMs: Long
        ): Boolean = versionCode <= snoozedVersionCode && nowMs - snoozedAtMs in 0 until SNOOZE_MS
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The published build if it is newer than [installedVersionCode]. Blocks: call off the main thread. */
    fun fetchNewerBuild(installedVersionCode: Long): UpdateInfo? {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Self-update manifest returned HTTP ${conn.responseCode}")
                return null
            }

            val update = parseUpdateInfo(conn.inputStream.bufferedReader().use { it.readText() })
            if (update == null) {
                Log.d(TAG, "Self-update manifest is invalid")
                return null
            }
            if (update.versionCode <= installedVersionCode) {
                Log.d(TAG, "Installer is up to date (build $installedVersionCode)")
                return null
            }
            Log.i(TAG, "Newer build ${update.versionCode} found (local=$installedVersionCode): ${update.url}")
            return update
        } catch (e: Exception) {
            Log.d(TAG, "Self-update check skipped: ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
    }

    fun isSnoozed(versionCode: Long): Boolean = isSnoozed(
        versionCode,
        prefs.getLong("snoozedVersionCode", 0L),
        prefs.getLong("snoozedAt", 0L),
        System.currentTimeMillis()
    )

    fun snooze(versionCode: Long) {
        prefs.edit {
            putLong("snoozedVersionCode", versionCode)
            putLong("snoozedAt", System.currentTimeMillis())
        }
    }
}
