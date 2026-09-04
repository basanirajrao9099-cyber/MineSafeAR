package com.minesafear

import android.app.Application
import com.minesafear.sync.SyncScheduler

/**
 * Application entry point.
 *
 * Deliberately thin: the Room database is created lazily by
 * [com.minesafear.data.DatabaseProvider] on first use, so only the sync schedule
 * needs registering up front.
 */
class MineSafeArApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Idempotent — the unique work policy keeps an already-running schedule.
        SyncScheduler.schedulePeriodicSync(this)
        // Nothing to do for localization here, deliberately. On Android 13+ the
        // system has already applied the per-app locale before this runs; on 10–12
        // MainActivity.attachBaseContext wraps its own context from
        // LanguagePreference. Wrapping the Application instead looks tidier and is
        // a trap: the framework rewrites the application configuration on every
        // config change, so the override silently lapses. See AppLocaleManager.
    }
}
