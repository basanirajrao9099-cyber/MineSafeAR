package com.minesafear.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.minesafear.data.entity.TrainingModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingModuleDao {

    @Upsert
    suspend fun upsertAll(modules: List<TrainingModuleEntity>)

    @Query("SELECT * FROM training_modules ORDER BY category ASC, title ASC")
    fun observeAll(): Flow<List<TrainingModuleEntity>>

    @Query("SELECT * FROM training_modules WHERE id = :moduleId")
    fun observeById(moduleId: String): Flow<TrainingModuleEntity?>

    @Query("SELECT * FROM training_modules WHERE id = :moduleId")
    suspend fun getById(moduleId: String): TrainingModuleEntity?

    @Query("DELETE FROM training_modules WHERE id NOT IN (:keptModuleIds)")
    suspend fun deleteMissing(keptModuleIds: List<String>)
}
