package com.minesafear.ui.modules.fire

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Short confirmation cues for in-scene decisions: a tone and a buzz.
 *
 * ## Why [ToneGenerator] and not an audio file
 *
 * It is built into the platform, so the "success sound" needs no asset, no
 * licence, and no download for a phone that will spend its life offline. The tones
 * are also the ones the platform already uses for accept and reject, so they are
 * not something a trainee has to learn.
 *
 * Haptics matter more than the tone here, and are not a nicety: a worker in a mine
 * is wearing hearing protection next to running plant, and will feel the phone
 * before they hear it.
 *
 * Every call fails soft. A device that cannot allocate a tone generator — some
 * cannot, and some fail while a call is in progress — should still be able to run
 * a fire drill.
 */
@Stable
class DrillCues internal constructor(private val haptics: HapticFeedback) {

    /**
     * Created eagerly and kept: allocating a generator per cue costs tens of
     * milliseconds and would land exactly on the tap it is meant to confirm.
     */
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT)
    } catch (e: RuntimeException) {
        // Thrown when the platform has no free tone resources.
        Log.w(TAG, "ToneGenerator unavailable; cues will be silent", e)
        null
    }

    /** An object landed in the scene. Quiet — this happens seven times per drill. */
    fun placed() {
        buzz(HapticFeedbackType.TextHandleMove)
        play(ToneGenerator.TONE_PROP_BEEP, SHORT_MS)
    }

    /** The trainee got it right. */
    fun correct() {
        buzz(HapticFeedbackType.LongPress)
        play(ToneGenerator.TONE_PROP_ACK, LONG_MS)
    }

    /** The trainee got it wrong. */
    fun wrong() {
        buzz(HapticFeedbackType.LongPress)
        play(ToneGenerator.TONE_PROP_NACK, LONG_MS)
    }

    /** A nudge, not a verdict: felt but not sounded. */
    fun hint() {
        buzz(HapticFeedbackType.TextHandleMove)
    }

    internal fun release() {
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator.release failed", e)
        }
    }

    private fun play(toneType: Int, durationMs: Int) {
        val generator = toneGenerator ?: return
        try {
            generator.startTone(toneType, durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "startTone failed", e)
        }
    }

    private fun buzz(type: HapticFeedbackType) {
        try {
            haptics.performHapticFeedback(type)
        } catch (e: Exception) {
            Log.w(TAG, "haptic feedback failed", e)
        }
    }

    private companion object {
        const val TAG = "DrillCues"

        /** Loud enough to hear over a fan, quiet enough not to startle. */
        const val VOLUME_PERCENT = 80

        const val SHORT_MS = 90
        const val LONG_MS = 220
    }
}

@Composable
fun rememberDrillCues(): DrillCues {
    val haptics = LocalHapticFeedback.current
    val cues = remember(haptics) { DrillCues(haptics) }
    DisposableEffect(cues) {
        onDispose { cues.release() }
    }
    return cues
}
