package com.minesafear.localization

import android.content.Context

/**
 * Stores the worker's language choice on this device.
 *
 * ## SharedPreferences rather than DataStore
 *
 * The whole state is one enum value read synchronously from
 * [android.app.Activity.attachBaseContext], which runs before a coroutine scope
 * exists and cannot suspend. DataStore's API is `suspend`/`Flow` by design, so
 * using it here would mean `runBlocking` on the main thread at every activity
 * launch — strictly worse than the thing it replaces. DataStore earns its keep for
 * larger, observable settings; this is not that.
 *
 * ## Who reads this
 *
 * On Android 13+ the *system* holds the choice ([AppLocaleManager] writes it to
 * `LocaleManager`), and this file is only a mirror kept for diagnostics and for a
 * downgrade path. On Android 10–12 there is no system store, so this file is the
 * source of truth and [AppLocaleManager.wrap] reads it on every activity create.
 */
object LanguagePreference {

    fun read(context: Context): AppLanguage? {
        val stored = prefs(context).getString(KEY_TAG, null)
        return if (stored.isNullOrBlank()) null else AppLanguage.fromTag(stored)
    }

    /**
     * Uses `commit()`, not `apply()`, on purpose.
     *
     * The next thing the caller does is recreate the activity, and on Android 10–12
     * the recreated activity reads this back synchronously. `apply()` would be
     * visible in-process either way, but a write that is still queued when the
     * process dies loses the choice — and a language picker that forgets what you
     * picked is the specific failure this screen exists to avoid. One short string
     * is a cheap fsync.
     */
    fun write(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_TAG, language.tag).commit()
    }

    /** Drops the override so the app follows the system language again. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TAG).commit()
    }

    private fun prefs(context: Context) =
        // applicationContext: attachBaseContext callers pass a context whose own
        // configuration is what we are about to override.
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val FILE = "minesafear_localization"
    private const val KEY_TAG = "app_language_tag"
}
