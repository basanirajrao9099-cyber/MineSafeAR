package com.minesafear.narration

import androidx.annotation.RawRes
import com.minesafear.localization.AppLanguage

/** An instructional overlay that has, or is meant to have, a spoken recording. */
enum class NarrationSlot {
    /** The Fire &amp; Explosion Response briefing. */
    FIRE_BRIEFING,

    /**
     * The Gas Leak &amp; Confined Space Protocol briefing.
     *
     * The slot exists ahead of the module. That is deliberate rather than an
     * oversight: it is the one place the recording list is enumerated, so a
     * narration that has to be commissioned, recorded and reviewed by a Santali
     * speaker should be visible here before the screen that plays it is written.
     * Wire it up in the gas module's briefing overlay when that module lands.
     */
    GAS_LEAK_BRIEFING,
}

/**
 * Which recording, if any, exists for a slot in a given language.
 *
 * ## Why a catalogue instead of `res/raw-hi/`
 *
 * The obvious design is one `fire_briefing.mp3` per locale directory and let the
 * resource system pick. It is wrong here, for one reason: resource fallback is
 * *silent*. A missing `res/raw-sat/fire_briefing.mp3` resolves to the English
 * recording, so a worker who selected Santali presses play and hears a confident
 * voice in a language they may not speak — and, worse, has no way to tell that
 * something went missing. For safety instructions that is worse than silence.
 *
 * Text can fall back safely, because a supervisor can read English off the screen
 * and translate on the spot. Audio cannot: it plays once, unattended, and is gone.
 * So audio is looked up explicitly, per language, and a `null` means the UI says
 * "not recorded in this language yet" instead of playing the wrong one. That is why
 * strings live in `values-<qualifier>/` and narration does not.
 *
 * ## Where the files go
 *
 * All recordings live in **`res/raw/`** with a language suffix — not in
 * `res/raw-<qualifier>/`, which would reintroduce the silent fallback above:
 *
 * ```
 * res/raw/fire_briefing_en.mp3
 * res/raw/fire_briefing_hi.mp3
 * res/raw/fire_briefing_sat.mp3
 * res/raw/gas_leak_briefing_en.mp3
 * res/raw/gas_leak_briefing_hi.mp3
 * res/raw/gas_leak_briefing_sat.mp3
 * ```
 *
 * Drop a file in, replace the matching `null` below with its `R.raw.…` id, and the
 * play button in that language stops being disabled. Nothing else changes.
 *
 * ## Why Santali needs a recorded file and not TTS
 *
 * Android's `TextToSpeech` has no reliable Santali voice — no bundled engine ships
 * one, in either script, so synthesising the briefing would either fail or fall
 * through to a Hindi voice mispronouncing Santali text. A recorded narration is the
 * only honest option, which also makes it the most valuable one on a site where
 * some workers read slowly or not at all.
 */
object NarrationCatalogue {

    @RawRes
    fun resourceFor(slot: NarrationSlot, language: AppLanguage): Int? = when (slot) {
        NarrationSlot.FIRE_BRIEFING -> fireBriefing(language)
        NarrationSlot.GAS_LEAK_BRIEFING -> gasLeakBriefing(language)
    }

    /** True when a local supervisor recording or raw resource exists for a slot. */
    fun isRecorded(context: android.content.Context, slot: NarrationSlot, language: AppLanguage): Boolean =
        hasLocalFile(context, slot, language) || (resourceFor(slot, language) != null)

    fun hasLocalFile(context: android.content.Context, slot: NarrationSlot, language: AppLanguage): Boolean {
        val dir = java.io.File(context.filesDir, "narration")
        val file = java.io.File(dir, "${slot.name.lowercase()}_${language.tag}.aac")
        return file.exists() && file.length() > 0
    }

    @RawRes
    private fun fireBriefing(language: AppLanguage): Int? = when (language) {
        // TODO(narration): record res/raw/fire_briefing_en.mp3, then return
        //  R.raw.fire_briefing_en here. Script: fire_module_instruction_1 through _3
        //  in values/strings.xml, read in order.
        AppLanguage.ENGLISH -> null

        // TODO(narration): record res/raw/fire_briefing_hi.mp3, then return
        //  R.raw.fire_briefing_hi here. Script: the same three keys in
        //  values-hi/strings.xml.
        AppLanguage.HINDI -> null

        // TODO(narration): record res/raw/fire_briefing_sat.mp3, then return
        //  R.raw.fire_briefing_sat here. One recording serves both Santali rows:
        //  Ol Chiki and Devanagari are two scripts for the same spoken language, and
        //  nobody hears a script. Have the reviewer who fills in values-sat read
        //  their own translation aloud.
        AppLanguage.SANTALI_OL_CHIKI, AppLanguage.SANTALI_DEVANAGARI -> null
    }

    @RawRes
    private fun gasLeakBriefing(language: AppLanguage): Int? = when (language) {
        // TODO(narration): res/raw/gas_leak_briefing_en.mp3 -> R.raw.gas_leak_briefing_en.
        //  Blocked on the module itself: "Gas Leak & Confined Space Protocol" has not
        //  been built, so its briefing text does not exist to be read out yet.
        AppLanguage.ENGLISH -> null

        // TODO(narration): res/raw/gas_leak_briefing_hi.mp3 -> R.raw.gas_leak_briefing_hi.
        AppLanguage.HINDI -> null

        // TODO(narration): res/raw/gas_leak_briefing_sat.mp3 -> R.raw.gas_leak_briefing_sat.
        AppLanguage.SANTALI_OL_CHIKI, AppLanguage.SANTALI_DEVANAGARI -> null
    }
}
