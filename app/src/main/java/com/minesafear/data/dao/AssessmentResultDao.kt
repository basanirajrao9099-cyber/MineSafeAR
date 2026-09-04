package com.minesafear.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.minesafear.data.entity.AssessmentResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssessmentResultDao {

    /** Attempts are append-only, so a plain insert is correct here. */
    @Insert
    suspend fun insert(result: AssessmentResultEntity)

    @Query(
        "SELECT * FROM assessment_results WHERE worker_id = :workerId " +
            "ORDER BY submitted_at DESC"
    )
    fun observeForWorker(workerId: String): Flow<List<AssessmentResultEntity>>

    @Query(
        "SELECT * FROM assessment_results " +
            "WHERE worker_id = :workerId AND module_id = :moduleId " +
            "ORDER BY submitted_at DESC LIMIT 1"
    )
    suspend fun getLatestAttempt(workerId: String, moduleId: String): AssessmentResultEntity?

    @Query(
        "SELECT COUNT(*) FROM assessment_results " +
            "WHERE worker_id = :workerId AND module_id = :moduleId"
    )
    suspend fun countAttempts(workerId: String, moduleId: String): Int

    @Query("SELECT * FROM assessment_results WHERE pending_sync = 1")
    suspend fun getPendingSync(): List<AssessmentResultEntity>

    @Query("SELECT COUNT(*) FROM assessment_results WHERE pending_sync = 1")
    fun observePendingSyncCount(): Flow<Int>

    @Query("UPDATE assessment_results SET pending_sync = 0 WHERE id IN (:resultIds)")
    suspend fun clearPendingSync(resultIds: List<String>)
}
