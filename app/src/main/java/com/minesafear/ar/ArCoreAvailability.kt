package com.minesafear.ar

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ar.core.ArCoreApk
import com.minesafear.R
import kotlinx.coroutines.delay

/**
 * Whether this phone can run an AR session at all, asked before trying.
 *
 * ## Why this exists
 *
 * ARCore is not part of Android. It ships as a separate app, "Google Play Services
 * for AR", and the manifest declares it optional so MineSafeAR installs on phones
 * that lack it. Without a check, those phones reach the drill screen, SceneView
 * fails to create a session, and the worker gets a black rectangle.
 *
 * The usual remedy — `ArCoreApk.requestInstall()` — **opens the Play Store**, which
 * needs an internet connection. On the target device, in a mine, that dialog is a
 * dead end: a worker is asked to install something and then cannot. So this file
 * deliberately never calls `requestInstall`. It reports, and the copy in
 * [ArCoreUnavailableGate] tells the worker to sort it out at the surface office.
 *
 * ## Offline behaviour, which is the point of the design
 *
 * `checkAvailability` may need a remote query to decide whether a *model* is
 * ARCore-capable, and returns one of the `UNKNOWN_*` values while it cannot. Those
 * are therefore treated as "carry on and try", not as "block": on a phone with no
 * signal, `UNKNOWN_TIMED_OUT` is the *expected* answer, and gating on it would lock
 * every offline worker out of AR training — exactly the failure this file is meant to
 * prevent. Only the three definitive negatives block. See [ArCoreStatus.blocksAr].
 *
 * ARCore caches a resolved support verdict, so the first check made with a connection
 * makes later offline checks definitive.
 *
 * **Known gap:** a phone that has never been online and has no ARCore installed
 * answers `UNKNOWN_TIMED_OUT`, so it is not gated and still reaches a failed session.
 * Closing that needs a session-creation failure callback from SceneView, which is a
 * larger change than this check.
 *
 * **Closed gap:** `SUPPORTED_INSTALLED` is not a promise that a session can be
 * created. Uncertified handsets answer it and then fail natively for want of a
 * camera calibration profile. [ArSessionProbe] settles that by creating a real
 * session, and [rememberArCoreStatus] folds its verdict into
 * [ArCoreStatus.DEVICE_UNSUPPORTED]. That probe needs the camera permission, so it
 * runs only once the permission is held — see the `cameraGranted` parameter.
 */
enum class ArCoreStatus(@StringRes val messageRes: Int?) {

    /** The query is still running, or could not reach a verdict. Do not block. */
    CHECKING(null),

    /** `SUPPORTED_INSTALLED` — go ahead. */
    READY(null),

    /** `SUPPORTED_NOT_INSTALLED` — the phone can do AR, the ARCore app is missing. */
    NOT_INSTALLED(R.string.ar_core_missing),

    /** `SUPPORTED_APK_TOO_OLD` — installed, but older than this build needs. */
    TOO_OLD(R.string.ar_core_outdated),

    /** `UNSUPPORTED_DEVICE_NOT_CAPABLE` — no update will fix this handset. */
    DEVICE_UNSUPPORTED(R.string.ar_core_device_unsupported),
    ;

    /**
     * True only when AR is known to be impossible.
     *
     * [CHECKING] covers both "still asking" and "could not find out", and is
     * deliberately permissive — see the note on offline behaviour above.
     */
    val blocksAr: Boolean get() = messageRes != null

    companion object {
        internal fun from(availability: ArCoreApk.Availability?): ArCoreStatus =
            when (availability) {
                null -> CHECKING
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> READY
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> NOT_INSTALLED
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> TOO_OLD
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> DEVICE_UNSUPPORTED
                // UNKNOWN_CHECKING / UNKNOWN_ERROR / UNKNOWN_TIMED_OUT, plus any
                // value a future ARCore adds.
                else -> CHECKING
            }
    }
}

/**
 * Polls [ArCoreApk.checkAvailability] until it settles, then holds the answer.
 *
 * Polling rather than a callback because that is the API ARCore offers: the first
 * call returns `UNKNOWN_CHECKING` while a query runs, and the caller is expected to
 * ask again. The loop is bounded so a query that never settles ends as [ArCoreStatus.CHECKING]
 * rather than spinning for the life of the screen.
 *
 * @param cameraGranted whether `CAMERA` is already held. Gates the second stage,
 *   [ArSessionProbe], which cannot distinguish a missing permission from an
 *   incapable device. Leave false and the result is availability-only, which is
 *   the pre-probe behaviour: safe, and blind to uncertified handsets.
 */
@Composable
fun rememberArCoreStatus(cameraGranted: Boolean = false): ArCoreStatus {
    val context = LocalContext.current
    var availability by remember(context) {
        mutableStateOf<ArCoreApk.Availability?>(null)
    }
    var probe by remember(context) { mutableStateOf(ArSessionProbeResult.UNKNOWN) }

    LaunchedEffect(context) {
        var polls = 0
        while (true) {
            val current = runCatching {
                ArCoreApk.getInstance().checkAvailability(context)
            }.getOrElse { error ->
                // A throw here is an ARCore internal problem, not a device verdict.
                // Logged and treated as unknown, which does not block AR.
                Log.w(TAG, "checkAvailability threw; treating as unknown", error)
                null
            }

            availability = current
            // isTransient() is only true for UNKNOWN_CHECKING; anything else,
            // including a null from the catch above, is as settled as it will get.
            if (current == null || !current.isTransient()) {
                Log.i(TAG, "ARCore availability: $current")
                break
            }
            if (++polls >= MAX_POLLS) {
                Log.w(TAG, "ARCore availability still checking after $polls polls; giving up")
                break
            }
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    val apkStatus = ArCoreStatus.from(availability)

    // Second stage. Only worth running when the apk says yes — every other status
    // is already a definitive answer, and probing would just duplicate it. Keyed on
    // cameraGranted so the probe starts the moment the permission lands.
    LaunchedEffect(context, apkStatus, cameraGranted) {
        if (apkStatus != ArCoreStatus.READY) return@LaunchedEffect
        if (!cameraGranted) return@LaunchedEffect
        if (probe != ArSessionProbeResult.UNKNOWN) return@LaunchedEffect
        probe = ArSessionProbe.run(context)
    }

    return if (apkStatus == ArCoreStatus.READY &&
        probe == ArSessionProbeResult.DEVICE_NOT_COMPATIBLE
    ) {
        // Same copy as a phone ARCore never listed, because to the worker it is the
        // same situation: this handset will not run the drills, the written modules
        // and their certificates still work.
        ArCoreStatus.DEVICE_UNSUPPORTED
    } else {
        apkStatus
    }
}

/**
 * Shown instead of the AR scene when [ArCoreStatus.blocksAr].
 *
 * Modelled on [ArCameraPermissionGate], and like it offers only a way out. There is
 * deliberately no "Install" or "Update" button: both would open the Play Store, and
 * a worker reading this underground cannot use it. The copy names the surface office
 * instead, which is an instruction they can actually follow.
 */
@Composable
fun ArCoreUnavailableGate(
    status: ArCoreStatus,
    onDismiss: () -> Unit,
    onContinueFallback: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val messageRes = status.messageRes ?: return

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.ar_core_unavailable_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (onContinueFallback != null) {
                TextButton(onClick = onContinueFallback) {
                    Text(text = "Continue in Camera & Gyro Fallback Mode")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ar_close))
            }
        }
    }
}

/** Roughly two seconds of polling, which is well past ARCore's own timeout. */
private const val POLL_INTERVAL_MILLIS = 200L
private const val MAX_POLLS = 10
private const val TAG = "ArCoreAvailability"
