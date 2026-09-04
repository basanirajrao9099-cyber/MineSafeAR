package com.minesafear.certificate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CertificatePayloadTest {

    @Test
    fun `encoding then parsing returns the same fields`() {
        val parsed = CertificatePayload.parse(payload().encode())

        assertEquals(payload(), parsed)
    }

    @Test
    fun `encoded form starts with the version prefix`() {
        val encoded = payload().encode()

        assertEquals(CertificatePayload.PREFIX, encoded.substringBefore('|'))
    }

    @Test
    fun `parse rejects a code with no prefix`() {
        val encoded = payload().encode().removePrefix("${CertificatePayload.PREFIX}|")

        assertNull(CertificatePayload.parse(encoded))
    }

    /** Version 1 codes carried no score, so they cannot be re-verified. */
    @Test
    fun `parse rejects an earlier version prefix`() {
        val encoded = payload().encode().replaceFirst(CertificatePayload.PREFIX, "MSAR1")

        assertNull(CertificatePayload.parse(encoded))
    }

    @Test
    fun `parse rejects a truncated code`() {
        val encoded = payload().encode().substringBeforeLast('|')

        assertNull(CertificatePayload.parse(encoded))
    }

    @Test
    fun `parse rejects an extra field`() {
        assertNull(CertificatePayload.parse(payload().encode() + "|extra"))
    }

    @Test
    fun `parse rejects a non-numeric score`() {
        assertNull(CertificatePayload.parse(encodedWith(score = "eighty")))
    }

    @Test
    fun `parse rejects non-numeric dates`() {
        assertNull(CertificatePayload.parse(encodedWith(issued = "yesterday")))
        assertNull(CertificatePayload.parse(encodedWith(expiry = "someday")))
    }

    @Test
    fun `parse rejects a signature that is not 64 hex characters`() {
        assertNull(CertificatePayload.parse(encodedWith(signature = "abc")))
        assertNull(CertificatePayload.parse(encodedWith(signature = "z".repeat(64))))
        // Uppercase hex is not what the signer emits, so it is not a signature the
        // recompute could ever match.
        assertNull(CertificatePayload.parse(encodedWith(signature = SIGNATURE.uppercase())))
    }

    @Test
    fun `parse rejects blank identifiers`() {
        assertNull(CertificatePayload.parse(encodedWith(certId = "")))
        assertNull(CertificatePayload.parse(encodedWith(userId = "   ")))
    }

    @Test
    fun `parse rejects an empty string and junk`() {
        assertNull(CertificatePayload.parse(""))
        assertNull(CertificatePayload.parse("https://example.com"))
    }

    @Test
    fun `a negative score still round trips`() {
        // Nothing should ever issue one, but a parser that silently dropped the sign
        // would hand the verifier different fields than were signed.
        val negative = payload().copy(score = -1)

        assertEquals(negative, CertificatePayload.parse(negative.encode()))
        assertNotNull(CertificatePayload.parse(negative.encode()))
    }

    private companion object {
        const val CERT_ID = "8a1f3c22-0000-4000-8000-000000000001"
        const val USER_ID = "local_worker"
        const val ISSUED = 1_760_000_000_000L
        const val SIGNATURE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun payload() = CertificatePayload(
            certId = CERT_ID,
            userId = USER_ID,
            score = 86,
            issuedDate = ISSUED,
            expiryDate = CertificatePolicy.expiryFor(ISSUED),
            signatureHash = SIGNATURE,
        )

        /** The encoded form with one field swapped for something malformed. */
        fun encodedWith(
            certId: String = CERT_ID,
            userId: String = USER_ID,
            score: String = "86",
            issued: String = ISSUED.toString(),
            expiry: String = CertificatePolicy.expiryFor(ISSUED).toString(),
            signature: String = SIGNATURE,
        ) = listOf(
            CertificatePayload.PREFIX, certId, userId, score, issued, expiry, signature,
        ).joinToString("|")
    }
}
