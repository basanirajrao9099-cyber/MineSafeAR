package com.minesafear.ar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Observable camera-permission state for the AR layer.
 *
 * Read [status] from a composable and it recomposes when the answer changes.
 *
 * SceneView can request the camera permission itself via its `permissionHandler`
 * parameter, but we own the flow instead so the app decides what the refusal
 * screen says — a worker who declines needs a route back into non-AR training,
 * not a blank viewfinder. `ArScene` therefore passes `permissionHandler = null`.
 */
@Stable
class ArCameraPermission internal constructor(
    private val statusProvider: () -> Status,
    private val requester: () -> Unit,
) {

    /** Current grant state. Backed by snapshot state, so reads are observable. */
    val status: Status get() = statusProvider()

    val isGranted: Boolean get() = status == Status.Granted

    /** Shows the system dialog. No-op once granted. */
    fun request() {
        if (status != Status.Granted) requester()
    }

    enum class Status {
        /** Not asked yet in this process, or asked and never answered. */
        Unknown,
        Granted,

        /**
         * Refused. Android gives no way to tell "declined once" from "declined
         * permanently" without an Activity, so treat both the same: explain why
         * the camera is needed and offer [openAppSettings] as the escape hatch.
         */
        Denied,
    }
}

/**
 * Creates an [ArCameraPermission] tied to the current composition.
 *
 * @param requestOnFirstShow ask immediately on first composition. Convenient for
 *   a dedicated AR screen, where the camera is the entire point. Pass `false`
 *   when the permission should follow an explicit user action instead.
 */
@Composable
fun rememberArCameraPermission(requestOnFirstShow: Boolean = true): ArCameraPermission {
    val context = LocalContext.current
    var status by remember(context) {
        mutableStateOf(
            if (context.hasCameraPermission()) {
                ArCameraPermission.Status.Granted
            } else {
                ArCameraPermission.Status.Unknown
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = if (granted) ArCameraPermission.Status.Granted else ArCameraPermission.Status.Denied
    }

    // Granting in system Settings does not call back into the launcher, so
    // re-read on every resume. Cheap, and it is the only way the "Denied ->
    // Settings -> back" path recovers without a restart.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (context.hasCameraPermission()) status = ArCameraPermission.Status.Granted
    }

    LaunchedEffect(requestOnFirstShow) {
        if (requestOnFirstShow && status == ArCameraPermission.Status.Unknown) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // The lambdas read/write `status` at call time, so the holder itself never
    // needs recreating and stays a stable Compose parameter.
    return remember(launcher) {
        ArCameraPermission(
            statusProvider = { status },
            requester = { launcher.launch(Manifest.permission.CAMERA) },
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens this app's system settings page, the only recovery once the camera
 * permission has been refused twice and the dialog stops appearing.
 */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
