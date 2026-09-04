package com.minesafear.certificate

import com.minesafear.data.entity.ModuleResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateVerifierTest {

    @Test
    fun `a freshly issued certificate is valid`() {
        val result = CertificateVerifier.verify(signedCode().encode(), nowMillis = ISSUED)

        assertTrue(result.toString(), result is CertificateVerification.Valid)
        assertEquals(CERT_ID, result.payload?.certId)
    }

    @Test
    fun `the millisecond before expiry is still valid`() {
        val result = CertificateVerifier.verify(
            signedCode().encode(),
            nowMillis = CertificatePolicy.expiryFor(ISSUED) - 1,
        )

        assertTrue(result.toString(), result is CertificateVerification.Valid)
    }

    /** Expiry is exclusive: the certificate is spent the instant it is reached. */
    @Test
    fun `the expiry instant itself is expired`() {
        val result = CertificateVerifier.verify(
            signedCode().encode(),
            nowMillis = CertificatePolicy.expiryFor(ISSUED),
        )

        assertTrue(result.toString(), result is CertificateVerification.Expired)
    }

    @Test
    fun `a certificate scanned long after expiry is expired, not invalid`() {
        val result = CertificateVerifier.verify(
            signedCode().encode(),
            nowMillis = ISSUED + 5L * CertificatePolicy.VALIDITY_MILLIS,
        )

        // The distinction matters: EXPIRED means retrain, INVALID means investigate.
        assertTrue(result.toString(), result is CertificateVerification.Expired)
        assertEquals(CERT_ID, result.payload?.certId)
    }

    @Test
    fun `raising the score invalidates the signature`() {
        val tampered = signedCode().copy(score = 100)

        val result = CertificateVerifier.verify(tampered.encode(), nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.SIGNATURE_MISMATCH,
            (result as CertificateVerification.Invalid).reason,
        )
    }

    @Test
    fun `swapping in another worker's id invalidates the signature`() {
        val tampered = signedCode().copy(userId = "someone_else")

        val result = CertificateVerifier.verify(tampered.encode(), nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.SIGNATURE_MISMATCH,
            (result as CertificateVerification.Invalid).reason,
        )
    }

    /**
     * The expiry is not one of the signed fields, so the verifier re-derives it from
     * the signed issue date. Stretching it is caught even though the signature still
     * matches.
     */
    @Test
    fun `extending the expiry date is caught even with a matching signature`() {
        val tampered = signedCode().copy(
            expiryDate = CertificatePolicy.expiryFor(ISSUED) + CertificatePolicy.VALIDITY_MILLIS,
        )

        val result = CertificateVerifier.verify(tampered.encode(), nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.EXPIRY_ALTERED,
            (result as CertificateVerification.Invalid).reason,
        )
    }

    @Test
    fun `shortening the expiry date is caught too`() {
        val tampered = signedCode().copy(expiryDate = ISSUED)

        val result = CertificateVerifier.verify(tampered.encode(), nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.EXPIRY_ALTERED,
            (result as CertificateVerification.Invalid).reason,
        )
    }

    @Test
    fun `a signature from a different certificate does not transfer`() {
        val other = CertificateIssuer.issue(
            userId = "other_worker",
            userName = "Other Worker",
            snapshot = snapshot(),
            nowMillis = ISSUED,
            certId = "8a1f3c22-0000-4000-8000-00000000000f",
        )!!
        val forged = signedCode().copy(signatureHash = other.signatureHash)

        val result = CertificateVerifier.verify(forged.encode(), nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.SIGNATURE_MISMATCH,
            (result as CertificateVerification.Invalid).reason,
        )
    }

    @Test
    fun `an unrecognised code is not a certificate`() {
        val result = CertificateVerifier.verify("https://example.com", nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.NOT_A_CERTIFICATE,
            (result as CertificateVerification.Invalid).reason,
        )
        // Nothing to show: there are no fields to present as scanned.
        assertNull(result.payload)
    }

    @Test
    fun `a cancelled scan is not a certificate`() {
        val result = CertificateVerifier.verify(null, nowMillis = ISSUED)

        assertEquals(
            CertificateVerification.Invalid.Reason.NOT_A_CERTIFICATE,
            (result as CertificateVerification.Invalid).reason,
        )
    }

    private companion object {
        const val CERT_ID = "8a1f3c22-0000-4000-8000-000000000001"
        const val USER_ID = "local_worker"
        const val ISSUED = 1_760_000_000_000L

        fun snapshot() = CertificateIssuer.snapshot(
            results = listOf(
                moduleResult("fire_explosion_response", 90),
                moduleResult("gas_leak_protocol", 82),
            ),
            userId = USER_ID,
        )

        /** A real issued certificate, so the signature under test is a real one. */
        fun signedCode(): CertificatePayload = CertificateIssuer.issue(
            userId = USER_ID,
            userName = "Test Worker",
            snapshot = snapshot(),
            nowMillis = ISSUED,
            certId = CERT_ID,
        )!!.toPayload()

        fun moduleResult(moduleId: String, score: Int) =
            ModuleResultEntity(
                id = "$moduleId-$score",
                moduleId = moduleId,
                userId = USER_ID,
                score = score,
                timestamp = ISSUED,
                passed = true,
                durationSeconds = 120,
                correctTaps = 2,
                incorrectTaps = 0,
            )
    }
}
