package com.nethunter.kalidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkVerifierTest {
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun sha256Hex_hashesBytes() {
        assertEquals(abcSha256, ApkVerifier.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun sha256Hex_hashesFileContents() {
        val file = File.createTempFile("sha256", ".bin")
        try {
            file.writeText("abc")
            assertEquals(abcSha256, ApkVerifier.sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun isSignedByAny_requiresATrustedSigner() {
        val trusted = "30b9525c0e8f3b7d32de877cb41ff8651dabc9dbf263555b2ffd200bf1f894ee"
        val other = "eb9e6a88121a66cd5acc7beb20c2e67415085b556018bf3616f3448df651ff4f"

        assertTrue(ApkVerifier.isSignedByAny(setOf(trusted), setOf(trusted)))
        // Rotated key: lineage still contains the trusted certificate
        assertTrue(ApkVerifier.isSignedByAny(setOf(trusted, other), setOf(trusted)))
        assertFalse(ApkVerifier.isSignedByAny(setOf(other), setOf(trusted)))
        // Unsigned archive or unreadable installed signers must never match
        assertFalse(ApkVerifier.isSignedByAny(emptySet(), setOf(trusted)))
        assertFalse(ApkVerifier.isSignedByAny(setOf(trusted), emptySet()))
    }
}
