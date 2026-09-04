package com.minesafear.data.repository

import com.minesafear.data.MineSafeArDatabase
import com.minesafear.data.entity.AssessmentResultEntity
import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.entity.ModuleProgressEntity
import com.minesafear.data.entity.ModuleResultEntity
import com.minesafear.data.entity.ProgressStatus
import com.minesafear.data.entity.TrainingModuleEntity
import com.minesafear.data.entity.WorkerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The only type the UI layer should talk to for persisted state. Keeping the DAOs
 * behind it means the eventual switch to a read-through/remote-backed source does
 * not touch any screen.
 */
class TrainingRepository(private val database: MineSafeArDatabase) {

    private val workerDao get() = database.workerDao()
    private val moduleDao get() = database.trainingModuleDao()
    private val progressDao get() = database.moduleProgressDao()
    private val resultDao get() = database.assessmentResultDao()
    private val moduleResultDao get() = database.moduleResultDao()
    private val certificateDao get() = database.certificateDao()

    // --- Workers ---------------------------------------------------------

    fun observeWorker(workerId: String): Flow<WorkerEntity?> = workerDao.observeById(workerId)

    fun observeAllWorkers(): Flow<List<WorkerEntity>> = workerDao.observeAll()

    suspend fun upsertWorker(worker: WorkerEntity) = workerDao.upsert(worker)

    suspend fun findWorkerByBadge(employeeCode: String): WorkerEntity? =
        workerDao.getByEmployeeCode(employeeCode)

    // --- Modules ---------------------------------------------------------

    fun observeModules(): Flow<List<TrainingModuleEntity>> = moduleDao.observeAll()

    fun observeModule(moduleId: String): Flow<TrainingModuleEntity?> =
        moduleDao.observeById(moduleId)

    // --- Progress --------------------------------------------------------

    fun observeProgress(workerId: String): Flow<List<ModuleProgressEntity>> =
        progressDao.observeForWorker(workerId)

    fun observeCompletedModuleCount(workerId: String): Flow<Int> =
        progressDao.observeCompletedCount(workerId)

    /**
     * Records that [workerId] reached [completedSteps] in [moduleId]. Marks the row
     * pending so the sync worker picks it up on its next run.
     */
    suspend fun recordProgress(
        workerId: String,
        moduleId: String,
        completedSteps: Int,
        totalSteps: Int,
        nowMillis: Long,
    ) {
        val completed = totalSteps > 0 && completedSteps >= totalSteps
        progressDao.upsert(
            ModuleProgressEntity(
                workerId = workerId,
                moduleId = moduleId,
                status = when {
                    completed -> ProgressStatus.COMPLETED
                    completedSteps > 0 -> ProgressStatus.IN_PROGRESS
                    else -> ProgressStatus.NOT_STARTED
                },
                completedSteps = completedSteps,
                lastAccessedAt = nowMillis,
                completedAt = nowMillis.takeIf { completed },
                pendingSync = true,
            )
        )
    }

    // --- Assessments -----------------------------------------------------

    fun observeResults(workerId: String): Flow<List<AssessmentResultEntity>> =
        resultDao.observeForWorker(workerId)

    suspend fun nextAttemptNumber(workerId: String, moduleId: String): Int =
        resultDao.countAttempts(workerId, moduleId) + 1

    suspend fun saveResult(result: AssessmentResultEntity) = resultDao.insert(result)

    // --- Interactive module results --------------------------------------

    fun observeModuleResults(userId: String): Flow<List<ModuleResultEntity>> =
        moduleResultDao.observeForUser(userId)

    fun observeModuleResults(userId: String, moduleId: String): Flow<List<ModuleResultEntity>> =
        moduleResultDao.observeForModule(userId, moduleId)

    suspend fun bestModuleScore(userId: String, moduleId: String): Int? =
        moduleResultDao.getBestScore(userId, moduleId)

    suspend fun moduleAttemptCount(userId: String, moduleId: String): Int =
        moduleResultDao.countAttempts(userId, moduleId)

    /**
     * Records one AR drill attempt.
     *
     * Deliberately does *not* also roll the attempt into `module_progress`, which
     * is what you would expect it to do. `ModuleProgressEntity` has foreign keys to
     * `workers` and `training_modules`, and nothing populates either table yet, so
     * writing progress here would throw a constraint violation on the very path
     * that is meant to be the offline-safe one — losing the result to save a
     * summary of it.
     *
     * Wire the roll-up up once workers and modules are provisioned (by sign-in, or
     * by seeding `training_modules` from a bundled catalogue). Until then the
     * results table is the record of truth for module completion.
     */
    suspend fun saveModuleResult(result: ModuleResultEntity) =
        moduleResultDao.insert(result)

    /** One-shot read for callers that aggregate once, such as certificate issue. */
    suspend fun moduleResultsFor(userId: String): List<ModuleResultEntity> =
        moduleResultDao.getForUser(userId)

    // --- Certificates ----------------------------------------------------

    fun observeCertificates(userId: String): Flow<List<CertificateEntity>> =
        certificateDao.observeForUser(userId)

    fun observeCertificate(certId: String): Flow<CertificateEntity?> =
        certificateDao.observeById(certId)

    suspend fun saveCertificate(certificate: CertificateEntity) =
        certificateDao.upsert(certificate)

    /**
     * Null when the certificate was not issued on this device — which is the normal
     * case when verifying somebody else's card, not an error.
     */
    suspend fun findCertificate(certId: String): CertificateEntity? =
        certificateDao.getById(certId)

    // --- Sync ------------------------------------------------------------

    /**
     * How many records are waiting to be uploaded, across both syncable tables.
     *
     * Drives the Home screen's sync indicator, which only distinguishes zero from
     * non-zero — the number is summed rather than reported per table because "you
     * have 2 drills and 1 certificate to upload" is detail a worker cannot act on.
     *
     * `module_progress` and `assessment_results` also carry a `pending_sync` column
     * and are deliberately excluded: [com.minesafear.sync.SyncApiService] has no
     * endpoint for either, so counting them would show a queue that no sync run can
     * ever drain. Add them here and there together.
     */
    fun observePendingSyncCount(): Flow<Int> = combine(
        moduleResultDao.observePendingSyncCount(),
        certificateDao.observePendingSyncCount(),
        resultDao.observePendingSyncCount(),
    ) { results, certificates, assessments -> results + certificates + assessments }

    /** The rows [com.minesafear.sync.SyncWorker] uploads. */
    suspend fun pendingModuleResults(): List<ModuleResultEntity> =
        moduleResultDao.getPendingSync()

    suspend fun pendingCertificates(): List<CertificateEntity> =
        certificateDao.getPendingSync()

    suspend fun pendingAssessmentResults(): List<AssessmentResultEntity> =
        resultDao.getPendingSync()

    /**
     * Marks the named results as uploaded.
     *
     * Takes ids rather than clearing the whole table for the reason spelled out on
     * `ModuleResultDao.clearPendingSync`: only what the server acknowledged is
     * synced, and a drill finished mid-upload has not been.
     */
    suspend fun markModuleResultsSynced(resultIds: List<String>) {
        if (resultIds.isEmpty()) return
        moduleResultDao.clearPendingSync(resultIds)
    }

    suspend fun markCertificatesSynced(certIds: List<String>) {
        if (certIds.isEmpty()) return
        certificateDao.clearPendingSync(certIds)
    }

    suspend fun markAssessmentResultsSynced(resultIds: List<String>) {
        if (resultIds.isEmpty()) return
        resultDao.clearPendingSync(resultIds)
    }

    companion object {
        /**
         * Stands in for the signed-in worker until sign-in exists.
         *
         * Every drill result is written against this id, so switching to real
         * identities means migrating these rows or discarding them. One constant so
         * there is one thing to delete.
         */
        const val UNPROVISIONED_USER_ID: String = "local_worker"
    }
}
