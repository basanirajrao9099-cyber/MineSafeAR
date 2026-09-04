package com.minesafear.sync

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The backend contract for uploading locally-recorded training records.
 *
 * A Retrofit interface, but **no Retrofit client is built anywhere in the app yet**
 * — [SyncApi] hands out [FakeSyncApiService], which implements this in process. The
 * annotations are here so that pointing at a real backend is a change to [SyncApi]
 * and nothing else.
 *
 * ## Shape
 *
 * Upload-only, and one endpoint per record type. Not a single `POST /sync` taking
 * everything, because the two record types have different consequences: a lost
 * drill attempt is a gap in a training history, while a lost certificate is a
 * worker who cannot prove they are allowed underground. Separate calls let the
 * worker retry one without re-sending the other.
 *
 * Nothing here pulls. Content download (module catalogue, worker profiles) is the
 * other half of sync and is not built — see the TODO in [SyncWorker].
 *
 * ## Why `Response<T>` and not a bare `T`
 *
 * [SyncWorker] has to tell a 500 (retry, the server is having a bad day) from a 400
 * (do not retry, this payload will never be accepted). A bare return type throws
 * `HttpException` for both and the distinction has to be recovered from the
 * exception; `Response` puts the status where the decision is made.
 */
interface SyncApiService {

    @POST("v1/module-results")
    suspend fun uploadModuleResults(
        @Body batch: SyncBatch<ModuleResultDto>,
    ): Response<SyncAck>

    @POST("v1/certificates")
    suspend fun uploadCertificates(
        @Body batch: SyncBatch<CertificateDto>,
    ): Response<SyncAck>
}

/**
 * Where [SyncWorker] gets its transport.
 *
 * One switch, in one place, so that "is this build talking to a real server" has a
 * single answer. Today it is always no.
 *
 * ## Pointing this at a real backend
 *
 * Three things, in order:
 *
 * 1. **Add a converter factory.** Retrofit is declared but no serializer is, so a
 *    `Retrofit.Builder()` without `addConverterFactory` will throw on the first
 *    call with a `@Body`. Pick one and add the artifact:
 *    `retrofit2-kotlinx-serialization-converter` (needs the
 *    `org.jetbrains.kotlin.plugin.serialization` plugin and `@Serializable` on the
 *    DTOs — check its interaction with AGP's built-in Kotlin first),
 *    `converter-moshi`, or `converter-gson` for the least ceremony.
 * 2. **Decide the on-wire casing** and annotate the DTOs rather than renaming
 *    them. See the note in `SyncPayloads.kt`.
 * 3. **Replace [service] with a lazily-built client.** Keep it lazy: this object is
 *    touched from `SyncWorker.doWork`, which runs on a WorkManager thread, and an
 *    eager OkHttp client would build a connection pool and dispatcher on every
 *    process start whether or not a sync ever runs.
 *
 * Do not add an interceptor that logs bodies to logcat in a release build. These
 * payloads carry worker names and certificate signatures.
 */
object SyncApi {

    /**
     * The fake is a single long-lived instance so its call counter survives across
     * worker runs, which is what makes "did the retry actually re-send" visible in
     * logcat.
     */
    private val fake: SyncApiService = FakeSyncApiService()

    /**
     * @param context reserved for the real implementation, which needs it for a
     *   cache directory and for reading the endpoint out of a build config. Unused
     *   by the fake, and kept in the signature so wiring the real client is not a
     *   call-site change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun service(context: android.content.Context): SyncApiService = fake
}
