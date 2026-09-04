package com.minesafear.certificate

import com.minesafear.data.entity.ModuleResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateIssuerTest {

    @Test
    fun `no module results means not eligible`() {
        val snapshot = CertificateIssuer.snapshot(emptyList(), USER_ID)

        assertEquals(emptyList<CertificateIssuer.ModuleScore>(), snapshot.moduleScores)
        assertEquals(0, snapshot.averageScore)
        assertFalse(snapshot.eligible)
    }

    @Test
    fun `one passing module is enough to be eligible`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(result("fire_explosion_response", 90)),
            USER_ID,
        )

        assertEquals(90, snapshot.averageScore)
        assertTrue(snapshot.eligible)
        assertEquals(listOf("fire_explosion_response"), snapshot.modulesCompleted)
    }

    @Test
    fun `only the best attempt at a module counts`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(
                result("fire_explosion_response", 40),
                result("fire_explosion_response", 95),
                result("fire_explosion_response", 60),
            ),
            USER_ID,
        )

        assertEquals(1, snapshot.moduleScores.size)
        assertEquals(95, snapshot.moduleScores.single().bestScore)
        assertEquals(95, snapshot.averageScore)
    }

    @Test
    fun `one weak module pulls the average below the threshold`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(
                result("fire_explosion_response", 100),
                result("gas_leak_protocol", 40),
            ),
            USER_ID,
        )

        assertEquals(70, snapshot.averageScore)
        assertFalse(snapshot.eligible)
    }

    @Test
    fun `exactly the threshold is eligible`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(result("fire_explosion_response", THRESHOLD)),
            USER_ID,
        )

        assertEquals(THRESHOLD, snapshot.averageScore)
        assertTrue(snapshot.eligible)
    }

    @Test
    fun `one mark below the threshold is not eligible`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(result("fire_explosion_response", THRESHOLD - 1)),
            USER_ID,
        )

        assertFalse(snapshot.eligible)
    }

    /** Floored, so a fraction of a mark can never round a worker into a pass. */
    @Test
    fun `the average is floored, not rounded`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(
                result("fire_explosion_response", 80),
                result("gas_leak_protocol", 79),
            ),
            USER_ID,
        )

        // The true mean is 79.5.
        assertEquals(79, snapshot.averageScore)
        assertFalse(snapshot.eligible)
    }

    @Test
    fun `another worker's results are ignored`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(
                result("fire_explosion_response", 95, userId = "someone_else"),
                result("gas_leak_protocol", 40),
            ),
            USER_ID,
        )

        assertEquals(listOf("gas_leak_protocol"), snapshot.modulesCompleted)
        assertEquals(40, snapshot.averageScore)
        assertFalse(snapshot.eligible)
    }

    @Test
    fun `modules are listed in a stable order`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(
                result("gas_leak_protocol", 90),
                result("fire_explosion_response", 90),
            ),
            USER_ID,
        )

        assertEquals(
            listOf("fire_explosion_response", "gas_leak_protocol"),
            snapshot.modulesCompleted,
        )
    }

    @Test
    fun `issue returns null when the worker is not eligible`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(result("fire_explosion_response", 10)),
            USER_ID,
        )

        assertNull(
            CertificateIssuer.issue(USER_ID, "Test Worker", snapshot, ISSUED, CERT_ID),
        )
    }

    @Test
    fun `an issued certificate carries the snapshot's score and modules`() {
        val snapshot = CertificateIssuer.snapshot(
            listOf(
                result("fire_explosion_response", 90),
                result("gas_leak_protocol", 82),
            ),
            USER_ID,
        )

        val certificate =
            CertificateIssuer.issue(USER_ID, "Test Worker", snapshot, ISSUED, CERT_ID)!!

        assertEquals(CERT_ID, certificate.certId)
        assertEquals(USER_ID, certificate.userId)
        assertEquals("Test Worker", certificate.userName)
        assertEquals(86, certificate.score)
        assertEquals(
            listOf("fire_explosion_response", "gas_leak_protocol"),
            certificate.modulesCompleted,
        )
        assertEquals(ISSUED, certificate.issuedDate)
    }

    @Test
    fun `an issued certificate expires one validity period after issue`() {
        val certificate = CertificateIssuer.issue(
            USER_ID, "Test Worker", eligibleSnapshot(), ISSUED, CERT_ID,
        )!!

        assertEquals(CertificatePolicy.expiryFor(ISSUED), certificate.expiryDate)
        assertEquals(ISSUED + CertificatePolicy.VALIDITY_MILLIS, certificate.expiryDate)
    }

    /** The round trip that matters: what the issuer signs, the verifier accepts. */
    @Test
    fun `an issued certificate verifies as valid`() {
        val certificate = CertificateIssuer.issue(
            USER_ID, "Test Worker", eligibleSnapshot(), ISSUED, CERT_ID,
        )!!

        val result = CertificateVerifier.verify(
            certificate.toPayload().encode(),
            nowMillis = ISSUED,
        )

        assertTrue(result.toString(), result is CertificateVerification.Valid)
    }

    private companion object {
        const val CERT_ID = "8a1f3c22-0000-4000-8000-000000000001"
        const val USER_ID = "local_worker"
        const val ISSUED = 1_760_000_000_000L
        const val THRESHOLD = CertificateIssuer.ELIGIBILITY_THRESHOLD_PERCENT

        fun eligibleSnapshot() = CertificateIssuer.snapshot(
            listOf(result("fire_explosion_response", 90), result("gas_leak_protocol", 82)),
            USER_ID,
        )

        /** [id] is derived from the arguments so repeated attempts stay distinct. */
        fun result(
            moduleId: String,
            score: Int,
            userId: String = USER_ID,
        ) = ModuleResultEntity(
            id = "$userId-$moduleId-$score",
            moduleId = moduleId,
            userId = userId,
            score = score,
            timestamp = ISSUED,
            passed = score >= THRESHOLD,
            durationSeconds = 120,
            correctTaps = 2,
            incorrectTaps = 0,
        )
    }
}
