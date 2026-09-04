package com.minesafear.localization

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the worker's language choice.
 *
 * ## Which API, and why the split
 *
 * `LocaleManager.setApplicationLocales` — the platform per-app language API — is
 * **API 33+**. Our `minSdk` is 29, so it covers three of the four API levels we
 * ship to and nothing on Android 10, 11 or 12. There are exactly two ways to close
 * that gap, and it is worth being precise about them because the choice has a
 * visible consequence:
 *
 * 1. **`AppCompatDelegate.setApplicationLocales`** (`androidx.appcompat` 1.6+)
 *    backports it. The catch is *where* the backport does its work: on API < 33 it
 *    takes effect through `AppCompatActivity.attachBaseContext`, so it only applies
 *    to activities that extend `AppCompatActivity`. `MainActivity` extends
 *    `ComponentActivity`, and the app theme parents `android:Theme.Material.Light`,
 *    which is not an AppCompat theme — so adopting it means three coordinated
 *    changes (declare the dependency, reparent the theme to
 *    `Theme.AppCompat.DayNight.NoActionBar`, change the superclass) or it throws
 *    "You need to use a Theme.AppCompat theme" at launch.
 * 2. **Wrap the context ourselves**, which is what appcompat is doing on your
 *    behalf anyway. That is [wrap], called from `MainActivity.attachBaseContext`.
 *
 * This project takes option 2: no new dependency, no theme change, and the app
 * keeps its plain-Compose activity. **So yes — a manual locale-wrapping approach
 * *is* required on API 29–32.** If the project later adopts `AppCompatActivity`
 * for other reasons, delete [wrap] and hand the whole thing to
 * `AppCompatDelegate.setApplicationLocales`; appcompat is already on the classpath
 * transitively via `zxing-android-embedded`, so only the explicit declaration and
 * the theme parent would be new.
 *
 * ## What each path stores
 *
 * On API 33+ the system owns the choice: it survives reinstalls, appears under
 * Settings › System › Languages (via `android:localeConfig`), and recreates our
 * activities for us. [LanguagePreference] is only a mirror there.
 *
 * On API 29–32 [LanguagePreference] is the source of truth, [wrap] reads it at
 * every activity create, and the caller has to recreate the activity itself —
 * [setLanguage] says which case it is in.
 *
 * ## The limit of context wrapping
 *
 * Wrapping happens per activity, so anything that pulls strings from the
 * *application* context — a `SyncWorker` notification, say — still resolves in the
 * system language on API 29–32. That is also true of the appcompat backport. The
 * fix for such a caller is to route it through [wrap] explicitly rather than to
 * wrap the Application, whose configuration the framework resets underneath you.
 */
object AppLocaleManager {

    /** What the caller must do after [setLanguage] returns. */
    enum class Applied {
        /** The platform changed the configuration; activities are being recreated. */
        BY_SYSTEM,

        /** Persisted only. Call `Activity.recreate()` to pick it up. */
        NEEDS_RECREATE,
    }

    fun setLanguage(context: Context, language: AppLanguage): Applied {
        // Written in both cases: on 13+ it is a mirror, below that it is the store.
        LanguagePreference.write(context, language)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
            if (localeManager != null) {
                localeManager.applicationLocales = LocaleList.forLanguageTags(language.tag)
            }
        }
        // Always return NEEDS_RECREATE so activity recreates immediately on all API levels.
        return Applied.NEEDS_RECREATE
    }

    /**
     * Returns [base] with the stored language applied, for use from
     * `Activity.attachBaseContext`.
     *
     * A no-op on API 33+, where the platform has already applied the locale to the
     * configuration before we are attached — wrapping again would be redundant and
     * would fight the system if the worker changed the language from Settings.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = LanguagePreference.read(base) ?: return base
        val locale = language.locale

        // Also the JVM default, so DateFormat and NumberFormat follow the choice.
        // Certificate dates are formatted against Locale.getDefault(), and a Hindi
        // screen showing English month names is the kind of half-translated state
        // that makes people distrust the rest of it. appcompat does the same.
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        // All three languages are LTR; this costs nothing and is one less thing to
        // remember if Urdu is ever added.
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * The language actually in effect.
     *
     * Reads the system first on API 33+ so a change made from Settings › Languages
     * shows up in our picker, then falls back to whatever the resource system
     * resolved for the current configuration — which on API 29–32 is the result of
     * [wrap].
     */
    fun currentLanguage(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val applied = context.getSystemService(android.app.LocaleManager::class.java)
                ?.applicationLocales
            if (applied != null && !applied.isEmpty) {
                return AppLanguage.fromTag(applied[0]?.toLanguageTag())
            }
        }
        val resolved = context.resources.configuration.locales
        return AppLanguage.fromTag(
            resolved.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        )
    }

    /** True when the device itself persists the per-app language. */
    val isSystemBacked: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/**
 * Walks the wrapper chain rather than casting to find the hosting Activity.
 */
fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}
