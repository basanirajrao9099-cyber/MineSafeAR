package com.minesafear.ar

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates and immediately closes an ARCore [Session] to find out whether this
 * handset can actually run one.
 *
 * ## Why `ArCoreApk.checkAvailability` is not enough
 *
 * `checkAvailability` answers "is the ARCore app installed, and is this model on
 * Google's list". Both can be yes on a phone that still cannot run AR. Observed
 * on the POCO X6 Pro (`duchamp`, Android 16) on 2026-09-02: availability was
 * `SUPPORTED_INSTALLED` with ARCore 1.56 installed and enabled, and the failure
 * only surfaced when SceneView created the session:
 *
 * ```
 * No device profile available for build fingerprint
 *   POCO/duchamp_in/duchamp:16/BP2A.250605.031.A3/OS3.0.7.0.WNLINXM:user/release-keys
 * ArStatusErrorSpace::AR_UNAVAILABLE_DEVICE_NOT_COMPATIBLE
 * ```
 *
 * ARCore fetches a per-fingerprint camera calibration profile from its own
 * content provider at session-create time. Uncertified builds have no profile,
 * the lookup returns `NOT_FOUND`, and the native layer refuses. Nothing in the
 * manifest, the Gradle setup, or the ARCore version changes that.
 *
 * Without this probe the worker reaches the drill screen and gets a black
 * rectangle — the exact failure the "Known gap" note on [ArCoreStatus] describes.
 * A real session create is the only authoritative answer, so this closes it.
 *
 * ## Requires the camera permission
 *
 * `Session()` also fails when `CAMERA` has not been granted, and that failure
 * looks nothing like a device verdict but would poison the cache all the same.
 * Callers must only probe once the permission is held; a permission-shaped
 * failure returns [ArSessionProbeResult.UNKNOWN] and is deliberately not cached.
 *
 * ## Caching
 *
 * Keyed on [Build.FINGERPRINT], because that is exactly what ARCore keys its
 * profile lookup on: a system update can add support that a previous verdict
 * would otherwise hide forever.
 */
enum class ArSessionProbeResult {

    /** Not asked yet, or asked and the answer says nothing about the device. */
    UNKNOWN,

    /** A session was created and closed cleanly. */
    USABLE,

    /** `AR_UNAVAILABLE_DEVICE_NOT_COMPATIBLE`. No update will fix this handset. */
    DEVICE_NOT_COMPATIBLE,
}

object ArSessionProbe {

    /**
     * Runs the probe, or returns a cached verdict for this exact build.
     *
     * Never throws: any unrecognised failure is [ArSessionProbeResult.UNKNOWN],
     * which does not block AR. Being wrong in that direction costs a black
     * screen; being wrong in the other locks a capable phone out of training.
     */
    suspend fun run(context: Context): ArSessionProbeResult {
        cached(context)?.let { return it }

        val result = withContext(Dispatchers.IO) { createAndClose(context) }

        // Only a definitive verdict is worth keeping. UNKNOWN covers a missing
        // permission and transient ARCore faults, neither of which is a property
        // of the device.
        if (result != ArSessionProbeResult.UNKNOWN) {
            context.arProbePrefs()
                .edit()
                .putString(KEY_FINGERPRINT, Build.FINGERPRINT)
                .putString(KEY_RESULT, result.name)
                .apply()
        }
        Log.i(TAG, "ARCore session probe: $result")
        return result
    }

    /** Forces the next [run] to probe again, e.g. after an ARCore update. */
    fun invalidate(context: Context) {
        context.arProbePrefs().edit().clear().apply()
    }

    private fun createAndClose(context: Context): ArSessionProbeResult = try {
        // Closed straight away: this asks ARCore a question, it does not start a
        // session anyone renders. Leaving it open would make SceneView's own
        // session-create fail on a camera already in use.
        Session(context).close()
        ArSessionProbeResult.USABLE
    } catch (e: UnavailableDeviceNotCompatibleException) {
        Log.w(TAG, "ARCore rejected this device: ${Build.FINGERPRINT}", e)
        ArSessionProbeResult.DEVICE_NOT_COMPATIBLE
    } catch (e: UnavailableException) {
        // Not installed, too old, or apk-vs-sdk mismatch. checkAvailability
        // already covers these and maps them to their own ArCoreStatus, so do
        // not duplicate the verdict here.
        Log.w(TAG, "ARCore session probe inconclusive: ${e.javaClass.simpleName}")
        ArSessionProbeResult.UNKNOWN
    } catch (e: SecurityException) {
        // CAMERA not granted. Caller probed too early; say nothing about the
        // device rather than caching a wrong answer.
        Log.w(TAG, "ARCore session probe ran without the camera permission", e)
        ArSessionProbeResult.UNKNOWN
    } catch (e: Throwable) {
        // Vendor ARCore builds have been seen to throw and to abort natively.
        // Treated as unknown so an OEM quirk cannot block a working phone.
        Log.w(TAG, "ARCore session probe failed unexpectedly", e)
        ArSessionProbeResult.UNKNOWN
    }

    private fun cached(context: Context): ArSessionProbeResult? {
        val prefs = context.arProbePrefs()
        if (prefs.getString(KEY_FINGERPRINT, null) != Build.FINGERPRINT) return null
        val stored = prefs.getString(KEY_RESULT, null) ?: return null
        return runCatching { ArSessionProbeResult.valueOf(stored) }.getOrNull()
    }

    private fun Context.arProbePrefs() =
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "ar_session_probe"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_RESULT = "result"
    private const val TAG = "ArSessionProbe"
}
