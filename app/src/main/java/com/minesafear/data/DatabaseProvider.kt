package com.minesafear.data

import android.content.Context
import androidx.room.Room

/**
 * Hand-rolled singleton so the project has no DI framework yet. When Hilt (or
 * Koin) is introduced, this becomes a `@Provides` and callers stop reaching for
 * a global.
 */
object DatabaseProvider {

    @Volatile
    private var instance: MineSafeArDatabase? = null

    fun get(context: Context): MineSafeArDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

    private fun build(context: Context): MineSafeArDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            MineSafeArDatabase::class.java,
            MineSafeArDatabase.NAME,
        )
            // Safe only because no release has shipped; replace with real
            // migrations before the first field deployment. The flag drops all
            // tables rather than only the ones Room knows about.
            .fallbackToDestructiveMigration(true)
            .build()
}
