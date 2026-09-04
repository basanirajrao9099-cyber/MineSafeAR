package com.minesafear.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.minesafear.data.entity.WorkerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkerDao {

    @Upsert
    suspend fun upsert(worker: WorkerEntity)

    @Delete
    suspend fun delete(worker: WorkerEntity)

    @Query("SELECT * FROM workers WHERE id = :workerId")
    fun observeById(workerId: String): Flow<WorkerEntity?>

    @Query("SELECT * FROM workers WHERE id = :workerId")
    suspend fun getById(workerId: String): WorkerEntity?

    @Query("SELECT * FROM workers WHERE employee_code = :employeeCode")
    suspend fun getByEmployeeCode(employeeCode: String): WorkerEntity?

    @Query("SELECT * FROM workers ORDER BY full_name ASC")
    fun observeAll(): Flow<List<WorkerEntity>>

    /** Rows the sync worker still has to push. */
    @Query("SELECT * FROM workers WHERE synced_at IS NULL OR synced_at < updated_at")
    suspend fun getUnsynced(): List<WorkerEntity>

    @Query("UPDATE workers SET synced_at = :syncedAt WHERE id IN (:workerIds)")
    suspend fun markSynced(workerIds: List<String>, syncedAt: Long)
}
