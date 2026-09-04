package com.minesafear.narration

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.minesafear.localization.AppLocaleManager

/**
 * Plays a module's spoken briefing, when there is one for the current language.
 *
 * Module-agnostic on purpose — it lived in `ui/modules/fire/` while there was one
 * module, and the gas-leak briefing would have copied it. Which slot to play is a
 * [NarrationSlot]; whether a recording exists is [NarrationCatalogue]'s answer.
 *
 * ## The "stub" part
 *
 * The playback path is real and finished — [MediaPlayer] creation, completion
 * callback, stop, release, lifecycle disposal. What is missing is the audio files. A
 * narration of `null` makes [isAvailable] false and every call a logged no-op, so
 * the button can be shown disabled with an honest explanation rather than lying
 * about a feature.
 *
 * That is the right shape for this project. Many workers on a mine site read the
 * local language slowly or not at all, so spoken instructions are closer to a
 * requirement than a nicety — and a stub that has to be *replaced* tends to be
 * rewritten from scratch under deadline, while a stub that only needs a file dropped
 * into `res/raw/` tends to get finished. [NarrationCatalogue] lists the exact
 * filenames.
 */
@Stable
class BriefingNarration internal constructor(
    private val context: Context,
    @RawRes private val narrationRes: Int?,
) {

    /** False while the narration is unrecorded in the current language. */
    val isAvailable: Boolean get() = narrationRes != null

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null

    fun toggle() {
        if (isPlaying) stop() else play()
    }

    fun play() {
        val res = narrationRes
        if (res == null) {
            Log.i(TAG, "No narration recorded for this slot and language; skipping playback.")
            return
        }
        stop()

        val created = try {
            MediaPlayer.create(context, res)
        } catch (e: Exception) {
            // A corrupt or unsupported file must not take the drill down with it.
            Log.w(TAG, "MediaPlayer.create failed", e)
            null
        }
        if (created == null) {
            Log.w(TAG, "MediaPlayer.create returned null for narration $res")
            return
        }

        created.setOnCompletionListener {
            isPlaying = false
            releasePlayer()
        }
        created.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            isPlaying = false
            releasePlayer()
            true
        }

        player = created
        created.start()
        isPlaying = true
    }

    fun stop() {
        val current = player ?: return
        try {
            if (current.isPlaying) current.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaPlayer.stop in bad state", e)
        }
        isPlaying = false
        releasePlayer()
    }

    /** Called when the composition leaves. A leaked [MediaPlayer] holds an audio focus
     * request and a decoder, both of which are scarce. */
    internal fun release() {
        isPlaying = false
        releasePlayer()
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        // Clear callbacks first: release() can otherwise re-enter them.
        current.setOnCompletionListener(null)
        current.setOnErrorListener(null)
        try {
            current.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer.release failed", e)
        }
    }

    private companion object {
        const val TAG = "BriefingNarration"
    }
}

/**
 * Resolves [slot] against the language currently in effect and remembers a player
 * for it, released when the composition leaves.
 *
 * The language is read rather than passed in because a language change recreates the
 * activity, so there is no case where this composition outlives the choice it was
 * built from.
 */
@Composable
fun rememberBriefingNarration(slot: NarrationSlot): BriefingNarration {
    val context = LocalContext.current
    val narrationRes = NarrationCatalogue.resourceFor(
        slot = slot,
        language = AppLocaleManager.currentLanguage(context),
    )
    val narration = remember(context, narrationRes) {
        BriefingNarration(context = context, narrationRes = narrationRes)
    }
    DisposableEffect(narration) {
        onDispose { narration.release() }
    }
    return narration
}
