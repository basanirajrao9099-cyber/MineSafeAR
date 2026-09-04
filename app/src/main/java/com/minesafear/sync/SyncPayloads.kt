package com.minesafear.sync

import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.entity.ModuleResultEntity

/**
 * The wire shape of a sync upload.
 *
 * ## Why these are not the Room entities
 *
 * It would be shorter to post [ModuleResultEntity] straight down the wire. These
 * exist anyway because the two shapes answer to different owners: the entity
 * changes whenever the local schema changes, and the wire format changes only when
 * the backend agrees to it. Posting the entity would make every `ALTER TABLE` a
 * silent API change.
 *
 * There is already one live example. `module_results.user_id` is named `user_id`
 * here and `worker_id` everywhere else in the schema, and the entity's own KDoc
 * flags that it should be aligned before release. When it is renamed, the mapper
 * below changes and the wire format does not.
 *
 * `pending_sync` is deliberately absent from every DTO: it is local bookkeeping
 * about whether we have talked to the server, and the server has no business
 * hearing our opinion of that.
 *
 * ## Field naming
 *
 * `camelCase`, matching Kotlin, because no serializer is wired up yet and there is
 * therefore nothing to disagree with. Whoever adds the converter factory decides
 * the on-wire casing and should annotate here rather than rename — see [SyncApi].
 */

/** One AR drill attempt. Mirrors [ModuleResultEntity] minus the sync bookkeeping. */
data class ModuleResultDto(
    val id: String,
    val moduleId: String,
    val userId: String,
    val score: Int,
    val timestamp: Long,
    val passed: Boolean,
    val durationSeconds: Int,
    val correctTaps: Int,
    val incorrectTaps: Int,
)

/**
 * One issued certificate, including [signatureHash].
 *
 * The hash travels so the backend can verify the card it is being handed rather
 * than re-deriving one and trusting us. Note that it is only as strong as the
 * shared salt in `CertificateSigner` — the production note there applies to this
 * payload too, and a server that treats an uploaded hash as proof of anything is
 * trusting a constant compiled into an APK.
 */
data class CertificateDto(
    val certId: String,
    val userId: String,
    val userName: String,
    val score: Int,
    val modulesCompleted: List<String>,
    val issuedDate: Long,
    val expiryDate: Long,
    val signatureHash: String,
)

/** One written safety assessment attempt. Mirrors AssessmentResultEntity minus pending_sync. */
data class AssessmentResultDto(
    val id: String,
    val workerId: String,
    val moduleId: String,
    val attemptNumber: Int,
    val scorePercent: Int,
    val correctAnswers: Int,
    val totalQuestions: Int,
    val passed: Boolean,
    val durationSeconds: Int,
    val submittedAt: Long,
)

/**
 * A batch upload.
 *
 * Batched rather than one request per row because a mine phone's window of
 * connectivity is measured in the seconds it spends near the surface office, and
 * forty round trips do not fit in it where one does.
 *
 * [deviceId] is not a user id. It identifies the handset so a backend can tell
 * "the same drill uploaded twice from one phone" (a retry) from "two phones
 * uploaded the same drill" (a restored backup), which is the difference between
 * deduplicating and investigating.
 */
data class SyncBatch<T>(
    val deviceId: String,
    /** Client clock at upload, epoch millis. Not authoritative — the server's is. */
    val sentAtMillis: Long,
    val records: List<T>,
)

/**
 * What the server says it accepted.
 *
 * [acceptedIds] rather than a bare 200 on purpose: a partially-accepted batch is
 * the normal case once validation exists on the far end, and the sync worker
 * clears the local `pending_sync` flag only for ids named here. A server that
 * silently drops a record cannot make us forget it.
 *
 * A null or absent [acceptedIds] is treated by [SyncWorker] as "all of them",
 * which is what a minimal backend that only returns 200 will do.
 */
data class SyncAck(
    val acceptedIds: List<String>? = null,
    /** Free-form, logged only. Somewhere for a backend to explain a rejection. */
    val message: String? = null,
)

// --- Mapping ------------------------------------------------------------------

fun ModuleResultEntity.toDto(): ModuleResultDto = ModuleResultDto(
    id = id,
    moduleId = moduleId,
    userId = userId,
    score = score,
    timestamp = timestamp,
    passed = passed,
    durationSeconds = durationSeconds,
    correctTaps = correctTaps,
    incorrectTaps = incorrectTaps,
)

fun CertificateEntity.toDto(): CertificateDto = CertificateDto(
    certId = certId,
    userId = userId,
    userName = userName,
    score = score,
    modulesCompleted = modulesCompleted,
    issuedDate = issuedDate,
    expiryDate = expiryDate,
    signatureHash = signatureHash,
)

fun com.minesafear.data.entity.AssessmentResultEntity.toDto(): AssessmentResultDto = AssessmentResultDto(
    id = id,
    workerId = workerId,
    moduleId = moduleId,
    attemptNumber = attemptNumber,
    scorePercent = scorePercent,
    correctAnswers = correctAnswers,
    totalQuestions = totalQuestions,
    passed = passed,
    durationSeconds = durationSeconds,
    submittedAt = submittedAt,
)

/**
 * The id [SyncAck.acceptedIds] refers to, per record type.
 *
 * Kept as functions next to the DTOs rather than as an interface both DTOs
 * implement, because the field names (`id` vs `certId`) are the backend's to
 * choose and an interface would force one of them to be renamed for our
 * convenience.
 */
fun ModuleResultDto.recordId(): String = id

fun CertificateDto.recordId(): String = certId

fun AssessmentResultDto.recordId(): String = id
