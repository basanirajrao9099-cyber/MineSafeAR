package com.minesafear.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A training module in the catalogue, ready to be seeded locally or pulled by sync.
 *
 * [arSceneAsset] names an `res/raw` resource **bundled in the APK** — never a URL,
 * and never a path into a download cache. Every model the app renders ships with it
 * (see `ar/ArModels`), because a drill that fetches its own scene is a drill a worker
 * underground cannot run. A row that arrives from sync naming an asset this build
 * does not contain must be treated as a module that cannot be started, not as
 * something to go and download.
 *
 * Null for modules that are slides only.
 */
@Entity(tableName = "training_modules")
data class TrainingModuleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    /** Free-form grouping shown in the module list, e.g. "Confined Space". */
    val category: String,
    @ColumnInfo(name = "ar_scene_asset") val arSceneAsset: String? = null,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "total_steps") val totalSteps: Int,
    /** Job roles this module is mandatory for; null means "everyone". */
    @ColumnInfo(name = "required_for_roles") val requiredForRoles: List<String>? = null,
    /** Bumped by the backend when content changes, so sync can replace stale rows. */
    @ColumnInfo(name = "content_version") val contentVersion: Int = 1,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
