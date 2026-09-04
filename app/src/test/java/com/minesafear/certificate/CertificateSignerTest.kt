package com.minesafear.certificate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateSignerTest {

    @Test
    fun `same inputs always produce the same signature`() {
        val first = CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED)
        val second = CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED)

        assertEquals(first, second)
    }

    @Test
    fun `signature is 64 lowercase hex characters`() {
        val signature = CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED)

        assertEquals(CertificateSigner.SIGNATURE_LENGTH_CHARS, signature.length)
        assertTrue(signature, Regex("[0-9a-f]{64}").matches(signature))
    }

    @Test
    fun `changing the certificate id changes the signature`() {
        assertNotEquals(
            CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED),
            CertificateSigner.signature("other-cert-id", USER_ID, SCORE, ISSUED),
        )
    }

    @Test
    fun `changing the user id changes the signature`() {
        assertNotEquals(
            CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED),
            CertificateSigner.signature(CERT_ID, "other_worker", SCORE, ISSUED),
        )
    }

    @Test
    fun `changing the score changes the signature`() {
        assertNotEquals(
            CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED),
            CertificateSigner.signature(CERT_ID, USER_ID, SCORE + 1, ISSUED),
        )
    }

    @Test
    fun `changing the issue date changes the signature`() {
        assertNotEquals(
            CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED),
            CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED + 1),
        )
    }

    /**
     * The reason the fields are joined with a separator rather than concatenated.
     * Without one, "ab" + "c" and "a" + "bc" hash identically, so a forger could
     * shift a character across the boundary and keep the signature valid.
     */
    @Test
    fun `moving a character across a field boundary changes the signature`() {
        assertNotEquals(
            CertificateSigner.signature("ab", "c", SCORE, ISSUED),
            CertificateSigner.signature("a", "bc", SCORE, ISSUED),
        )
    }

    @Test
    fun `matches accepts the signature it produced`() {
        val signature = CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED)

        assertTrue(CertificateSigner.matches(CERT_ID, USER_ID, SCORE, ISSUED, signature))
    }

    @Test
    fun `matches rejects a signature taken from different fields`() {
        val signature = CertificateSigner.signature(CERT_ID, USER_ID, SCORE, ISSUED)

        assertFalse(CertificateSigner.matches(CERT_ID, USER_ID, 100, ISSUED, signature))
    }

    @Test
    fun `matches rejects a signature of the wrong length`() {
        assertFalse(CertificateSigner.matches(CERT_ID, USER_ID, SCORE, ISSUED, ""))
        assertFalse(CertificateSigner.matches(CERT_ID, USER_ID, SCORE, ISSUED, "deadbeef"))
    }

    private companion object {
        const val CERT_ID = "8a1f3c22-0000-4000-8000-000000000001"
        const val USER_ID = "local_worker"
        const val SCORE = 86

        /** A fixed instant, so the assertions do not depend on when they run. */
        const val ISSUED = 1_760_000_000_000L
    }
}
