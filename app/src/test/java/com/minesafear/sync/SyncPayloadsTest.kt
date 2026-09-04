package com.minesafear.sync

import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.entity.ModuleResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Field-for-field checks on the entity-to-wire mappers.
 *
 * Tedious on purpose. These mappers are the one place a schema change can silently
 * alter what a backend receives, and the failure mode — a certificate uploaded with
 * somebody else's score in it — is invisible in a log full of successful 200s.
 */
class SyncPayloadsTest {

    @Test
    fun `module result maps every field`() {
        val dto = moduleResult().toDto()

        assertEquals("result-1", dto.id)
        assertEquals("fire_explosion_response", dto.moduleId)
        assertEquals("local_worker", dto.userId)
        assertEquals(85, dto.score)
        assertEquals(1_700_000_000_000L, dto.timestamp)
        assertEquals(true, dto.passed)
        assertEquals(143, dto.durationSeconds)
        assertEquals(3, dto.correctTaps)
        assertEquals(1, dto.incorrectTaps)
    }

    @Test
    fun `certificate maps every field including the signature`() {
        val dto = certificate().toDto()

        assertEquals("cert-1", dto.certId)
        assertEquals("local_worker", dto.userId)
        assertEquals("A. Worker", dto.userName)
        assertEquals(85, dto.score)
        assertEquals(listOf("fire_explosion_response"), dto.modulesCompleted)
        assertEquals(1_700_000_000_000L, dto.issuedDate)
        assertEquals(1_731_536_000_000L, dto.expiryDate)
        assertEquals("deadbeef", dto.signatureHash)
    }

    /**
     * The DTOs must not carry `pending_sync`. It is a local opinion about whether we
     * have managed to talk to the server, and a server that read it back would be
     * taking our word for its own state.
     */
    @Test
    fun `pending sync does not reach the wire`() {
        val result = moduleResult(pendingSync = true).toDto().toString()
        val cert = certificate(pendingSync = true).toDto().toString()

        assertFalse(result, result.contains("pending", ignoreCase = true))
        assertFalse(cert, cert.contains("pending", ignoreCase = true))
    }

    /** The id [SyncAck.acceptedIds] is matched against, per type. */
    @Test
    fun `record ids come from the right field`() {
        assertEquals("result-1", moduleResult().toDto().recordId())
        assertEquals("cert-1", certificate().toDto().recordId())
    }

    @Test
    fun `a batch carries the device and the records unchanged`() {
        val dtos = listOf(moduleResult().toDto(), moduleResult(id = "result-2").toDto())
        val batch = SyncBatch(deviceId = "device-abc", sentAtMillis = 42L, records = dtos)

        assertEquals("device-abc", batch.deviceId)
        assertEquals(42L, batch.sentAtMillis)
        assertEquals(listOf("result-1", "result-2"), batch.records.map { it.recordId() })
    }

    /** A minimal backend returns 200 and nothing else; that has to mean something. */
    @Test
    fun `an empty ack defaults to no explicit ids`() {
        assertEquals(null, SyncAck().acceptedIds)
        assertEquals(null, SyncAck().message)
    }

    private fun moduleResult(
        id: String = "result-1",
        pendingSync: Boolean = true,
    ) = ModuleResultEntity(
        id = id,
        moduleId = "fire_explosion_response",
        userId = "local_worker",
        score = 85,
        timestamp = 1_700_000_000_000L,
        passed = true,
        durationSeconds = 143,
        correctTaps = 3,
        incorrectTaps = 1,
        pendingSync = pendingSync,
    )

    private fun certificate(pendingSync: Boolean = true) = CertificateEntity(
        certId = "cert-1",
        userId = "local_worker",
        userName = "A. Worker",
        score = 85,
        modulesCompleted = listOf("fire_explosion_response"),
        issuedDate = 1_700_000_000_000L,
        expiryDate = 1_731_536_000_000L,
        signatureHash = "deadbeef",
        pendingSync = pendingSync,
    )
}
