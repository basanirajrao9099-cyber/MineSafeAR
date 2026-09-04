package com.minesafear.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The stand-in transport, exercised through the same [SyncApiService] surface the
 * worker uses.
 *
 * `runBlocking` rather than `runTest`, because `kotlinx-coroutines-test` is not a
 * declared dependency; every case passes `latencyMillis = 0` so nothing actually
 * sleeps.
 */
class FakeSyncApiServiceTest {

    @Test
    fun `accepts everything and names the ids`() = runBlocking {
        val api = FakeSyncApiService(latencyMillis = 0)

        val response = api.uploadModuleResults(batch(listOf("a", "b", "c")))

        assertEquals(200, response.code())
        assertTrue(response.isSuccessful)
        assertEquals(listOf("a", "b", "c"), response.body()?.acceptedIds)
    }

    @Test
    fun `certificates use the same contract`() = runBlocking {
        val api = FakeSyncApiService(latencyMillis = 0)

        val response = api.uploadCertificates(
            SyncBatch(
                deviceId = "device-1",
                sentAtMillis = 1L,
                records = listOf(certificateDto("cert-1")),
            ),
        )

        assertEquals(200, response.code())
        assertEquals(listOf("cert-1"), response.body()?.acceptedIds)
    }

    /**
     * The case `acceptedIds` exists for. A 200 that only takes some records must not
     * be readable as "all done".
     */
    @Test
    fun `a partial accept names only what it took`() = runBlocking {
        val api = FakeSyncApiService(
            behaviour = FakeSyncApiService.Behaviour.PartiallyAccepting(acceptCount = 2),
            latencyMillis = 0,
        )

        val response = api.uploadModuleResults(batch(listOf("a", "b", "c")))

        assertEquals(200, response.code())
        assertEquals(listOf("a", "b"), response.body()?.acceptedIds)
        assertNotNull(response.body()?.message)
    }

    @Test
    fun `a partial accept of zero takes nothing`() = runBlocking {
        val api = FakeSyncApiService(
            behaviour = FakeSyncApiService.Behaviour.PartiallyAccepting(acceptCount = 0),
            latencyMillis = 0,
        )

        val response = api.uploadModuleResults(batch(listOf("a", "b")))

        assertEquals(emptyList<String>(), response.body()?.acceptedIds)
    }

    /** A negative count is a caller mistake, not a crash. */
    @Test
    fun `a negative accept count is clamped`() = runBlocking {
        val api = FakeSyncApiService(
            behaviour = FakeSyncApiService.Behaviour.PartiallyAccepting(acceptCount = -5),
            latencyMillis = 0,
        )

        val response = api.uploadModuleResults(batch(listOf("a")))

        assertEquals(emptyList<String>(), response.body()?.acceptedIds)
    }

    @Test
    fun `a server error surfaces its status and no body`() = runBlocking {
        val api = FakeSyncApiService(
            behaviour = FakeSyncApiService.Behaviour.Failing(code = 503),
            latencyMillis = 0,
        )

        val response = api.uploadModuleResults(batch(listOf("a")))

        assertEquals(503, response.code())
        assertTrue(!response.isSuccessful)
        assertNull(response.body())
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(response.code()))
    }

    @Test
    fun `a rejection is classified as permanent`() = runBlocking {
        val api = FakeSyncApiService(
            behaviour = FakeSyncApiService.Behaviour.Failing(code = 422),
            latencyMillis = 0,
        )

        val response = api.uploadCertificates(
            SyncBatch("device-1", 1L, listOf(certificateDto("cert-1"))),
        )

        assertEquals(422, response.code())
        assertEquals(SyncOutcome.PERMANENT_FAILURE, SyncOutcomes.forHttpStatus(response.code()))
    }

    /** What a mine actually does to a request: drops it mid-flight. */
    @Test
    fun `an unreachable host throws rather than returning a status`() = runBlocking {
        val api = FakeSyncApiService(
            behaviour = FakeSyncApiService.Behaviour.Unreachable,
            latencyMillis = 0,
        )

        val thrown = runCatching { api.uploadModuleResults(batch(listOf("a"))) }.exceptionOrNull()

        assertTrue(thrown.toString(), thrown is IOException)
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forException(thrown!!))
    }

    @Test
    fun `an empty batch is accepted without complaint`() = runBlocking {
        val api = FakeSyncApiService(latencyMillis = 0)

        val response = api.uploadModuleResults(batch(emptyList()))

        assertEquals(200, response.code())
        assertEquals(emptyList<String>(), response.body()?.acceptedIds)
    }

    private fun batch(ids: List<String>) = SyncBatch(
        deviceId = "device-1",
        sentAtMillis = 1L,
        records = ids.map(::moduleResultDto),
    )

    private fun moduleResultDto(id: String) = ModuleResultDto(
        id = id,
        moduleId = "fire_explosion_response",
        userId = "local_worker",
        score = 85,
        timestamp = 1_700_000_000_000L,
        passed = true,
        durationSeconds = 143,
        correctTaps = 3,
        incorrectTaps = 1,
    )

    private fun certificateDto(certId: String) = CertificateDto(
        certId = certId,
        userId = "local_worker",
        userName = "A. Worker",
        score = 85,
        modulesCompleted = listOf("fire_explosion_response"),
        issuedDate = 1_700_000_000_000L,
        expiryDate = 1_731_536_000_000L,
        signatureHash = "deadbeef",
    )
}
