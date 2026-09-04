package com.minesafear.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.minesafear.data.entity.ModuleProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleProgressDao {

    @Upsert
    suspend fun upsert(progress: ModuleProgressEntity)

    @Query("SELECT * FROM module_progress WHERE worker_id = :workerId")
    fun observeForWorker(workerId: String): Flow<List<ModuleProgressEntity>>

    @Query(
        "SELECT * FROM module_progress WHERE worker_id = :workerId AND module_id = :moduleId"
    )
    fun observeOne(workerId: String, moduleId: String): Flow<ModuleProgressEntity?>

    @Query(
        "SELECT * FROM module_progress WHERE worker_id = :workerId AND module_id = :moduleId"
    )
    suspend fun getOne(workerId: String, moduleId: String): ModuleProgressEntity?

    @Query(
        "SELECT COUNT(*) FROM module_progress " +
            "WHERE worker_id = :workerId AND status = 'completed'"
    )
    fun observeCompletedCount(workerId: String): Flow<Int>

    @Query("SELECT * FROM module_progress WHERE pending_sync = 1")
    suspend fun getPendingSync(): List<ModuleProgressEntity>

    @Query(
        "UPDATE module_progress SET pending_sync = 0 " +
            "WHERE worker_id = :workerId AND module_id = :moduleId"
    )
    suspend fun clearPendingSync(workerId: String, moduleId: String)
}
