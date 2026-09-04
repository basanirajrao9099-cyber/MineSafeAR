package com.minesafear.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.minesafear.data.entity.ModuleResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleResultDao {

    /** Attempts are append-only, so a plain insert is correct here. */
    @Insert
    suspend fun insert(result: ModuleResultEntity)

    @Query(
        "SELECT * FROM module_results WHERE user_id = :userId " +
            "ORDER BY timestamp DESC"
    )
    fun observeForUser(userId: String): Flow<List<ModuleResultEntity>>

    /**
     * One-shot equivalent of [observeForUser], for callers that aggregate once
     * rather than render continuously.
     */
    @Query(
        "SELECT * FROM module_results WHERE user_id = :userId " +
            "ORDER BY timestamp DESC"
    )
    suspend fun getForUser(userId: String): List<ModuleResultEntity>

    @Query(
        "SELECT * FROM module_results " +
            "WHERE user_id = :userId AND module_id = :moduleId " +
            "ORDER BY timestamp DESC"
    )
    fun observeForModule(userId: String, moduleId: String): Flow<List<ModuleResultEntity>>

    @Query(
        "SELECT * FROM module_results " +
            "WHERE user_id = :userId AND module_id = :moduleId " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLatestAttempt(userId: String, moduleId: String): ModuleResultEntity?

    /** Null when the worker has never attempted the module. */
    @Query(
        "SELECT MAX(score) FROM module_results " +
            "WHERE user_id = :userId AND module_id = :moduleId"
    )
    suspend fun getBestScore(userId: String, moduleId: String): Int?

    @Query(
        "SELECT COUNT(*) FROM module_results " +
            "WHERE user_id = :userId AND module_id = :moduleId"
    )
    suspend fun countAttempts(userId: String, moduleId: String): Int

    @Query("SELECT * FROM module_results WHERE pending_sync = 1")
    suspend fun getPendingSync(): List<ModuleResultEntity>

    /**
     * Drives the Home screen's sync indicator.
     *
     * A count rather than the rows: the indicator only needs to know whether the
     * queue is empty, and loading whole attempt rows to call `.size` on them would
     * re-read every queued drill on every insert.
     */
    @Query("SELECT COUNT(*) FROM module_results WHERE pending_sync = 1")
    fun observePendingSyncCount(): Flow<Int>

    /**
     * Clears the flag for named ids only.
     *
     * Not `UPDATE module_results SET pending_sync = 0` wholesale, which is the
     * tempting one-liner and is wrong: a drill finished while an upload was in
     * flight would be marked synced without ever having been sent. The ids come from
     * [com.minesafear.sync.SyncAck.acceptedIds].
     */
    @Query("UPDATE module_results SET pending_sync = 0 WHERE id IN (:resultIds)")
    suspend fun clearPendingSync(resultIds: List<String>)
}
