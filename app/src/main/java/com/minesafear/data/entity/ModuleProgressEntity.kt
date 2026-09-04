package com.minesafear.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** How far a worker has got through one module. */
@Entity(
    tableName = "module_progress",
    primaryKeys = ["worker_id", "module_id"],
    foreignKeys = [
        ForeignKey(
            entity = WorkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["worker_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrainingModuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["module_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("worker_id"), Index("module_id")],
)
data class ModuleProgressEntity(
    @ColumnInfo(name = "worker_id") val workerId: String,
    @ColumnInfo(name = "module_id") val moduleId: String,
    /** One of [ProgressStatus]. Stored as a plain string to keep migrations cheap. */
    val status: String = ProgressStatus.NOT_STARTED,
    @ColumnInfo(name = "completed_steps") val completedSteps: Int = 0,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)

object ProgressStatus {
    const val NOT_STARTED = "not_started"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
}
