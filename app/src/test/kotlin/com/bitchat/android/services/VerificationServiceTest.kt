package com.bitchat.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Parsing-level tests for QR verification URLs. These only exercise URI parsing
 * (no EncryptionService is required); signature validation is covered by
 * verifyScannedQR at runtime.
 */
@RunWith(RobolectricTestRunner::class)
class VerificationServiceTest {

    private fun qrUrl(scheme: String) =
        "$scheme://verify?v=1&noise=aa11bb22&sign=cc33dd44&nick=Alice&ts=1700000000&nonce=abc123&sig=ee55ff66"

    @Test
    fun `current deadair scheme parses`() {
        val qr = VerificationService.VerificationQR.fromUrlString(qrUrl("deadair"))
        assertNotNull(qr)
        assertEquals("Alice", qr?.nickname)
        assertEquals("aa11bb22", qr?.noiseKeyHex)
    }

    @Test
    fun `legacy bitchat scheme still parses`() {
        val qr = VerificationService.VerificationQR.fromUrlString(qrUrl("bitchat"))
        assertNotNull(qr)
        assertEquals("Alice", qr?.nickname)
        assertEquals("aa11bb22", qr?.noiseKeyHex)
    }

    @Test
    fun `scheme is case insensitive`() {
        val qr = VerificationService.VerificationQR.fromUrlString(
            qrUrl("deadair").replaceFirst("deadair", "DEADAIR")
        )
        assertNotNull(qr)
    }

    @Test
    fun `unknown scheme is rejected`() {
        assertNull(VerificationService.VerificationQR.fromUrlString(qrUrl("https")))
        assertNull(VerificationService.VerificationQR.fromUrlString(qrUrl("deadairx")))
    }

    @Test
    fun `wrong host is rejected`() {
        val url = "deadair://other?v=1&noise=aa&sign=bb&nick=Alice&ts=123&nonce=xyz&sig=cc"
        assertNull(VerificationService.VerificationQR.fromUrlString(url))
    }
}
