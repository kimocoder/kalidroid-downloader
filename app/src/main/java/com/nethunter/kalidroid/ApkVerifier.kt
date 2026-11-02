package com.nethunter.kalidroid

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** The installed [packageName], or null if it isn't installed (or isn't visible to us). */
internal fun Context.installedPackage(packageName: String = this.packageName, flags: Int = 0): PackageInfo? =
    runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull()

internal fun Context.installedVersionCode(): Long =
    installedPackage()?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L

/** Checks a downloaded APK is intact and comes from the expected publisher before it is installed. */
internal class ApkVerifier(context: Context) {

    companion object {
        // setup.apk must be one of these packages, signed by one of these certificates
        // (SHA-256, as printed by `apksigner verify --print-certs`). Add a new key here
        // and ship that installer before kalidroid.com starts serving APKs signed with it.
        // Keep the package names in sync with <queries> in AndroidManifest.xml.
        internal val SETUP_PACKAGE_NAMES = setOf("com.excp.kalidroid.debug")
        private val SETUP_SIGNER_SHA256 = setOf(
            "30b9525c0e8f3b7d32de877cb41ff8651dabc9dbf263555b2ffd200bf1f894ee"
        )

        // Some Android versions only collect (and verify) an archive's certificates
        // when GET_SIGNATURES is set, so always request it alongside the newer flag.
        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS = PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0

        /** True if any of [signers] (SHA-256 certificate digests) is in [trusted]. */
        internal fun isSignedByAny(signers: Set<String>, trusted: Set<String>): Boolean =
            signers.any { it in trusted }

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

    private val app = context.applicationContext

    /**
     * Checks [file] is an intact APK: for a self-update, a newer build of this app signed with
     * its key; otherwise setup.apk from the pinned publisher. Returns the archive's package info.
     *
     * @throws Exception with a user-facing message when a check fails
     */
    fun verify(file: File, isSelfUpdate: Boolean, expectedSha256: String?): PackageInfo {
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
                magic.take(magicRead).joinToString(" ") { "%02X".format(Locale.US, it) }
            } else {
                "empty"
            }
            Log.e(TAG, "Not a valid APK: $hex")
            throw Exception(app.getString(R.string.error_not_apk, hex))
        }

        if (expectedSha256 != null) {
            val actualSha256 = sha256Hex(file)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "SHA-256 mismatch: expected $expectedSha256, got $actualSha256")
                throw Exception(app.getString(R.string.error_checksum))
            }
        }

        // Also verifies the APK signature; a tampered archive comes back null
        val archiveInfo = app.packageManager.getPackageArchiveInfo(file.absolutePath, SIGNATURE_FLAGS)
        if (archiveInfo == null) {
            Log.e(TAG, "PackageArchiveInfo check failed for ${file.absolutePath}")
            throw Exception(app.getString(R.string.error_corrupted))
        }
        val archiveSigners = signerDigests(archiveInfo, includeHistory = true)

        if (isSelfUpdate) {
            // Only ever install a newer build of ourselves, so a stale release
            // asset can never put the self-update into a reinstall loop.
            val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
            val localVersionCode = app.installedVersionCode()
            if (archiveInfo.packageName != app.packageName || archiveVersionCode <= localVersionCode) {
                Log.e(
                    TAG,
                    "Rejected update ${archiveInfo.packageName} build $archiveVersionCode " +
                        "(installed build $localVersionCode)"
                )
                throw Exception(app.getString(R.string.error_update_not_newer))
            }
            if (!isSignedByAny(archiveSigners, installedSigners())) {
                Log.e(TAG, "Rejected update signed by $archiveSigners")
                throw Exception(app.getString(R.string.error_update_wrong_key))
            }
        } else if (archiveInfo.packageName !in SETUP_PACKAGE_NAMES ||
            !isSignedByAny(archiveSigners, SETUP_SIGNER_SHA256)
        ) {
            Log.e(TAG, "Rejected ${archiveInfo.packageName} signed by $archiveSigners")
            throw Exception(app.getString(R.string.error_wrong_publisher))
        }
        return archiveInfo
    }

    /** Certificate digests [file] is signed with, including ones a rotated key replaces. */
    internal fun archiveSigners(file: File): Set<String> =
        app.packageManager.getPackageArchiveInfo(file.absolutePath, SIGNATURE_FLAGS)
            ?.let { signerDigests(it, includeHistory = true) }
            .orEmpty()

    /** Certificate digests the installed copy of this app is currently signed with. */
    internal fun installedSigners(): Set<String> =
        app.installedPackage(flags = SIGNATURE_FLAGS)
            ?.let { signerDigests(it, includeHistory = false) }
            .orEmpty()

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
}
