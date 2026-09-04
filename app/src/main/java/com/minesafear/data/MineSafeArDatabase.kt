package com.minesafear.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.minesafear.data.converter.Converters
import com.minesafear.data.dao.AssessmentResultDao
import com.minesafear.data.dao.CertificateDao
import com.minesafear.data.dao.ModuleProgressDao
import com.minesafear.data.dao.ModuleResultDao
import com.minesafear.data.dao.TrainingModuleDao
import com.minesafear.data.dao.WorkerDao
import com.minesafear.data.entity.AssessmentResultEntity
import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.entity.ModuleProgressEntity
import com.minesafear.data.entity.ModuleResultEntity
import com.minesafear.data.entity.TrainingModuleEntity
import com.minesafear.data.entity.WorkerEntity

/**
 * The single offline store. Everything the app shows must be readable with no
 * network, so sync writes into these tables rather than being read through.
 *
 * `exportSchema` stays false until the first release ships; once it does, turn it
 * on, commit `app/schemas/`, and write real migrations instead of falling back to
 * a destructive one.
 *
 * ## Version history
 *
 * - **3** — reshaped `certificates` from one row per passed module to one row per
 *   certified worker (`cert_id`, `user_name`, `modules_completed`,
 *   `signature_hash`), and dropped its foreign keys for the same offline-first
 *   reason `module_results` has none.
 * - **2** — added `module_results` for interactive AR module attempts. No
 *   migration written: `DatabaseProvider` still falls back to a destructive one,
 *   which is only acceptable because nothing has shipped.
 * - **1** — initial schema.
 */
@Database(
    entities = [
        WorkerEntity::class,
        TrainingModuleEntity::class,
        ModuleProgressEntity::class,
        AssessmentResultEntity::class,
        ModuleResultEntity::class,
        CertificateEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MineSafeArDatabase : RoomDatabase() {

    abstract fun workerDao(): WorkerDao

    abstract fun trainingModuleDao(): TrainingModuleDao

    abstract fun moduleProgressDao(): ModuleProgressDao

    abstract fun assessmentResultDao(): AssessmentResultDao

    abstract fun moduleResultDao(): ModuleResultDao

    abstract fun certificateDao(): CertificateDao

    companion object {
        const val NAME = "minesafear.db"
    }
}
