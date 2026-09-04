package com.minesafear.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completed attempt at a module assessment. Attempts are kept rather than
 * overwritten so a site supervisor can audit the history offline.
 */
@Entity(
    tableName = "assessment_results",
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
data class AssessmentResultEntity(
    /** Locally generated UUID; the backend keeps the same value. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "worker_id") val workerId: String,
    @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "score_percent") val scorePercent: Int,
    @ColumnInfo(name = "correct_answers") val correctAnswers: Int,
    @ColumnInfo(name = "total_questions") val totalQuestions: Int,
    val passed: Boolean,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int,
    @ColumnInfo(name = "submitted_at") val submittedAt: Long,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)
