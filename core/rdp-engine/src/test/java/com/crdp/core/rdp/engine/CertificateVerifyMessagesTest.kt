package com.crdp.core.rdp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateVerifyMessagesTest {

    @Test
    fun zeroFlags_yieldsSingleUntrustedMessage() {
        val lines = CertificateVerifyMessages.certificateErrorMessages(0L)
        assertEquals(1, lines.size)
        assertEquals(
            "The certificate is not from a trusted certifying authority.",
            lines[0],
        )
    }

    @Test
    fun mismatchFlag_firstMessage() {
        val lines = CertificateVerifyMessages.certificateErrorMessages(VerifyCertificateFlags.MISMATCH)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("does not match", ignoreCase = true))
    }

    @Test
    fun combinedFlags_orderAndContent() {
        val flags = VerifyCertificateFlags.MISMATCH or
            VerifyCertificateFlags.GATEWAY or
            VerifyCertificateFlags.MATCH_LEGACY_SHA1
        val lines = CertificateVerifyMessages.certificateErrorMessages(flags)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("does not match", ignoreCase = true))
        assertTrue(lines[1].contains("Gateway", ignoreCase = true))
        assertTrue(lines[2].contains("SHA-1", ignoreCase = true))
    }

    @Test
    fun isPemFingerprint() {
        assertFalse(CertificateVerifyMessages.isPemFingerprint(0L))
        assertTrue(CertificateVerifyMessages.isPemFingerprint(VerifyCertificateFlags.FP_IS_PEM))
    }
}
