package com.minesafear.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The outcome of one attempt at an interactive AR training module.
 *
 * Distinct from [AssessmentResultEntity], which grades a written question set.
 * This grades *what the worker did in the scene* — which extinguisher they
 * reached for, which way they ran — so the columns that matter are the actions,
 * not the answers.
 *
 * Attempts are appended, never overwritten: a supervisor auditing a fatality
 * needs to see that the worker picked water twice before picking CO2, and a
 * worker's own retry history is how they see themselves improving.
 *
 * ## No foreign keys, deliberately
 *
 * The obvious schema would constrain [userId] to `workers.id` and [moduleId] to
 * `training_modules.id`, as [AssessmentResultEntity] does. It does not, because
 * this app is offline-first: a worker can be handed a phone and start a drill
 * before their profile has ever synced down, and a foreign key would turn that
 * into an insert that throws instead of a result that saves. Referential
 * integrity is the sync layer's job here, and a lost result is worse than a
 * dangling id.
 *
 * The indices are still worth having — every read is scoped by worker or module.
 */
@Entity(
    tableName = "module_results",
    indices = [Index("user_id"), Index("module_id")],
)
data class ModuleResultEntity(
    /** Locally generated UUID; the backend keeps the same value. */
    @PrimaryKey val id: String,
    /** Stable module identifier, e.g. `fire_explosion_response`. */
    @ColumnInfo(name = "module_id") val moduleId: String,
    /**
     * The worker who ran the drill.
     *
     * Named `user_id` rather than `worker_id`, which is the convention everywhere
     * else in this schema. Worth aligning on one name before the first release —
     * the sync payloads will inherit whichever wins.
     */
    @ColumnInfo(name = "user_id") val userId: String,
    /** 0–100. */
    val score: Int,
    /** When the attempt finished, epoch millis. */
    val timestamp: Long,
    val passed: Boolean,
    /** Wall-clock time from the end of the briefing to the last decision. */
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int,
    /** Correct in-scene decisions, e.g. the right extinguisher. */
    @ColumnInfo(name = "correct_taps") val correctTaps: Int,
    /** Wrong in-scene decisions. The interesting number for a trainer. */
    @ColumnInfo(name = "incorrect_taps") val incorrectTaps: Int,
    @ColumnInfo(name = "pending_sync") val pendingSync: Boolean = true,
)
