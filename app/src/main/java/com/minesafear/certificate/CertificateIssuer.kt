package com.minesafear.certificate

import com.minesafear.assessment.ScoringEngine
import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.entity.ModuleResultEntity
import com.minesafear.data.repository.TrainingRepository
import java.util.UUID

/**
 * Turns a worker's module results into a certificate.
 *
 * ## Standing in for AssessmentEngine
 *
 * The aggregation here — best attempt per module, floored average, one threshold —
 * is the minimum needed to answer "is this worker certified, and what goes on the
 * card". A dedicated `AssessmentEngine` owning certification *policy* (which
 * modules are mandatory, what the threshold is, how a partially-complete syllabus
 * reads) has not been built yet. When it is, it should call [issue] with the score
 * and module list it computed, and [snapshot] should be deleted rather than kept as
 * a second opinion. Until then this is the only place that decides, so it is the
 * only place to change.
 */
object CertificateIssuer {

    /**
     * Reuses the app-wide pass mark so a worker is not told they passed every
     * module and then refused a certificate. `AssessmentEngine` may want a
     * different, lower bar for the aggregate than for a single module; that is its
     * call to make, not this object's.
     */
    const val ELIGIBILITY_THRESHOLD_PERCENT: Int = ScoringEngine.PASS_THRESHOLD_PERCENT

    /** A module the worker has attempted, and the best score they reached on it. */
    data class ModuleScore(val moduleId: String, val bestScore: Int)

    data class CertificationSnapshot(
        /** Sorted by module id, so a re-issue produces the same card. */
        val moduleScores: List<ModuleScore>,
        val averageScore: Int,
        val eligible: Boolean,
    ) {
        val modulesCompleted: List<String> get() = moduleScores.map { it.moduleId }
    }

    /**
     * Aggregates [results] into what a certificate would say.
     *
     * Best attempt per module rather than latest or mean, matching
     * `bestModuleScore` elsewhere: a trainee who retries until they get it right
     * has learned the material, and penalising the practice would discourage it.
     *
     * The average is **floored** integer division. That is deliberate — the number
     * shown to the worker is then the same number that was judged, and a 79.5 can
     * never be rounded up into a pass.
     *
     * [userId] filters inside this function rather than at the call site so a query
     * that accidentally returns more than one worker's rows can never let one
     * worker's scores certify another.
     */
    fun snapshot(results: List<ModuleResultEntity>, userId: String): CertificationSnapshot {
        val moduleScores = results
            .filter { it.userId == userId }
            .groupBy { it.moduleId }
            .map { (moduleId, attempts) -> ModuleScore(moduleId, attempts.maxOf { it.score }) }
            .sortedBy { it.moduleId }

        val average = if (moduleScores.isEmpty()) {
            0
        } else {
            moduleScores.sumOf { it.bestScore } / moduleScores.size
        }

        return CertificationSnapshot(
            moduleScores = moduleScores,
            averageScore = average,
            // The empty check is not redundant with the threshold: it stops a
            // worker who has completed nothing from being certified if the
            // threshold is ever lowered to zero.
            eligible = moduleScores.isNotEmpty() && average >= ELIGIBILITY_THRESHOLD_PERCENT,
        )
    }

    /**
     * Builds a certificate, or null when [snapshot] is not eligible.
     *
     * Returning null rather than throwing keeps the gate in the model: a screen
     * whose button is wrongly enabled cannot mint a certificate, and a screen that
     * forgets to check does not crash.
     *
     * @param certId injectable so tests can assert on a known signature; production
     *   callers take the random default.
     */
    fun issue(
        userId: String,
        userName: String,
        snapshot: CertificationSnapshot,
        nowMillis: Long,
        certId: String = UUID.randomUUID().toString(),
    ): CertificateEntity? {
        if (!snapshot.eligible) return null
        return CertificateEntity(
            certId = certId,
            userId = userId,
            userName = userName,
            score = snapshot.averageScore,
            modulesCompleted = snapshot.modulesCompleted,
            issuedDate = nowMillis,
            expiryDate = CertificatePolicy.expiryFor(nowMillis),
            signatureHash = CertificateSigner.signature(
                certId = certId,
                userId = userId,
                score = snapshot.averageScore,
                issuedDate = nowMillis,
            ),
        )
    }

    /**
     * Issues and persists in one step. Null when the worker is not eligible or the
     * write failed — the caller shows the same "could not issue" either way, and
     * the alternative is an id pointing at a row that does not exist.
     */
    suspend fun issueAndSave(
        repository: TrainingRepository,
        userId: String,
        userName: String,
        snapshot: CertificationSnapshot,
        nowMillis: Long,
    ): CertificateEntity? {
        val certificate = issue(userId, userName, snapshot, nowMillis) ?: return null
        return runCatching { repository.saveCertificate(certificate) }
            .map { certificate }
            .getOrNull()
    }
}

/**
 * The QR-encodable view of a stored certificate. Derived every time rather than
 * stored, so the code and the row cannot disagree.
 */
fun CertificateEntity.toPayload(): CertificatePayload = CertificatePayload(
    certId = certId,
    userId = userId,
    score = score,
    issuedDate = issuedDate,
    expiryDate = expiryDate,
    signatureHash = signatureHash,
)
