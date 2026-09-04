package com.minesafear.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A worker enrolled in safety training. [id] is the server-issued identifier so
 * rows survive a re-sync; [employeeCode] is what the worker reads off their badge.
 */
@Entity(tableName = "workers")
data class WorkerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "employee_code") val employeeCode: String,
    @ColumnInfo(name = "full_name") val fullName: String,
    @ColumnInfo(name = "site_id") val siteId: String,
    @ColumnInfo(name = "job_role") val jobRole: String,
    /** BCP 47 tag chosen in Settings, e.g. `en` or `hi`. */
    @ColumnInfo(name = "preferred_language") val preferredLanguage: String = "en",
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Null until the row has been pushed to the backend. */
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
)
