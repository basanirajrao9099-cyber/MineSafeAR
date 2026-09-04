package com.minesafear.localization

import androidx.annotation.StringRes
import com.minesafear.R
import java.util.Locale

/**
 * Languages the app ships strings for.
 *
 * Adding one means: a new entry here, a matching `values-<qualifier>/strings.xml`,
 * a `<locale>` in `res/xml/locales_config.xml`, and the two name resources in
 * `values/strings.xml`.
 *
 * ## Why the names are resource ids and not translated strings
 *
 * [displayNameRes] points at a `translatable="false"` resource, which is the one
 * place in this app where that attribute is correct. A picker has to render every
 * language in its own script whatever locale is active, because the reason someone
 * opens it is that they cannot read the current one.
 *
 * [latinNameRes] is the same name in Latin script, or `null` when the two would be
 * identical. It is not decoration: Ol Chiki is missing from the bundled font set on
 * many Android builds, so [SANTALI_OL_CHIKI]'s display name can come out as empty
 * boxes on exactly the device whose owner is looking for it. The Latin line is what
 * keeps that row findable.
 *
 * ## Why Santali appears twice
 *
 * Ol Chiki is the script Santali is officially written in and is the primary
 * target. The Devanagari row exists for the font problem above, and because many
 * Santali speakers in Jharkhand and Odisha are schooled in Devanagari. Same
 * language, same words, different script — not Hindi.
 */
enum class AppLanguage(
    /** BCP 47 tag. Matches the resource qualifier: `hi` → `values-hi`, `sat-Deva` → `values-b+sat+Deva`. */
    val tag: String,
    @StringRes val displayNameRes: Int,
    @StringRes val latinNameRes: Int?,
    /**
     * False while `values-<qualifier>/strings.xml` is still mostly TODO comments.
     * Untranslated keys fall back to English at runtime, which is a state worth
     * warning about in the picker rather than letting a worker discover it.
     */
    val isFullyTranslated: Boolean,
) {
    ENGLISH(
        tag = "en",
        displayNameRes = R.string.language_name_en,
        latinNameRes = null,
        isFullyTranslated = true,
    ),
    HINDI(
        tag = "hi",
        displayNameRes = R.string.language_name_hi,
        latinNameRes = R.string.language_latin_hi,
        isFullyTranslated = true,
    ),
    SANTALI_OL_CHIKI(
        tag = "sat",
        displayNameRes = R.string.language_name_sat_olck,
        latinNameRes = R.string.language_latin_sat_olck,
        isFullyTranslated = false,
    ),
    SANTALI_DEVANAGARI(
        tag = "sat-Deva",
        displayNameRes = R.string.language_name_sat_deva,
        latinNameRes = R.string.language_latin_sat_deva,
        isFullyTranslated = false,
    ),
    ;

    /** The tag as a [Locale], for [android.os.LocaleList] and `Locale.setDefault`. */
    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        val DEFAULT = ENGLISH

        /**
         * Resolves a stored or system tag onto an entry, falling back to [DEFAULT].
         *
         * Script-aware, which it has to be: `sat` and `sat-Deva` share a language
         * subtag, so matching on that alone would collapse the Devanagari choice
         * into the Ol Chiki one and silently hand a worker the script they picked
         * *away* from. Region is ignored — `hi-IN` is [HINDI].
         *
         * A script we do not ship (`sat-Beng`, say) resolves to the first entry for
         * that language, so declaration order puts the script the language is
         * normally written in first.
         */
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return DEFAULT
            val requested = Locale.forLanguageTag(tag)
            val candidates = entries.filter {
                it.locale.language.isNotEmpty() && it.locale.language == requested.language
            }
            if (candidates.isEmpty()) return DEFAULT

            val script = requested.script
            if (script.isNotEmpty()) {
                candidates.firstOrNull { it.locale.script.equals(script, ignoreCase = true) }
                    ?.let { return it }
            }
            return candidates.first()
        }
    }
}
