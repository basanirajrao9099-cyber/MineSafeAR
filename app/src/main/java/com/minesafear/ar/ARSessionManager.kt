package com.minesafear.ar

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalViewConfiguration
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.PointCloud
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Represents an uneven ground feature, rock, debris, or raised obstacle
 * detected via ARCore depth & point cloud analysis.
 */
data class ArObstacle(
    val id: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val radiusMetres: Float = 0.35f,
    val description: String = "Uneven Surface / Rock Obstacle",
    /**
     * World Y of the floor plane under this cluster. The highlight ring is drawn
     * at floor level, not at the cluster centroid, so the outline reads as paint
     * on the ground rather than a halo floating at knee height.
     */
    val floorY: Float = y - 0.20f,
    /** Peak height of the cluster above [floorY], metres. Drives severity colour. */
    val heightMetres: Float = 0.20f,
)

/**
 * One model anchored to the world.
 *
 * [id] is a stable Compose key: [Anchor] has no meaningful identity across a
 * session restart, and using the anchor itself as a key would rebuild every node
 * whenever the list changed.
 */
class ArPlacement internal constructor(
    val id: Long,
    val anchor: Anchor,
    @RawRes val modelRes: Int,
    val scaleToUnits: Float,
    /**
     * Whatever the caller needs to recognise this placement later — a training
     * module stores the thing the model *represents* here, so that
     * [ARSessionManager.placementNear] answers "the trainee picked the foam
     * extinguisher" rather than "the trainee picked placement 3".
     *
     * Deliberately untyped: the AR layer has no business knowing what a module's
     * domain model looks like.
     */
    val tag: Any? = null,
    initialRotationY: Float = 0f,
    initialScaleMultiplier: Float = 1.0f,
    initialFloatHeight: Float = 0f,
) {
    /** Additional rotation around Y axis in degrees (0..360). */
    var rotationYDegrees: Float by mutableStateOf(initialRotationY)

    /** Scale multiplier applied on top of [scaleToUnits] (e.g. 0.2x..3.0x). */
    var userScaleMultiplier: Float by mutableStateOf(initialScaleMultiplier)

    /** Elevation height in metres above the anchored surface (0.0m..2.0m). */
    var floatHeightMetres: Float by mutableStateOf(initialFloatHeight)

    fun rotate(deltaDegrees: Float) {
        var newAngle = (rotationYDegrees + deltaDegrees) % 360f
        if (newAngle < 0f) newAngle += 360f
        rotationYDegrees = newAngle
    }

    fun setRotation(degrees: Float) {
        var newAngle = degrees % 360f
        if (newAngle < 0f) newAngle += 360f
        rotationYDegrees = newAngle
    }

    fun scaleBy(factor: Float) {
        userScaleMultiplier = (userScaleMultiplier * factor).coerceIn(0.2f, 3.0f)
    }

    fun setScaleMultiplier(multiplier: Float) {
        userScaleMultiplier = multiplier.coerceIn(0.2f, 3.0f)
    }

    fun setFloatHeight(heightMetres: Float) {
        floatHeightMetres = heightMetres.coerceIn(0.0f, 2.0f)
    }

    fun adjustFloatHeight(deltaMetres: Float) {
        floatHeightMetres = (floatHeightMetres + deltaMetres).coerceIn(0.0f, 2.0f)
    }
}

/**
 * Holds the state of one AR session: what has been placed, whether tracking is
 * healthy, and whether the camera is allowed at all.
 *
 * ## Why this is a state holder and not a session owner
 *
 * SceneView 4.x is declarative. `ARSceneView` creates, configures, resumes,
 * pauses and destroys the ARCore [Session] from the Compose lifecycle, and nodes
 * are *declared* in its `content` lambda rather than added imperatively. So the
 * useful thing for this class to own is the list of placements that the content
 * lambda renders — [placements] — plus the derived state a training screen needs
 * to guide the worker.
 *
 * That inverts the usual "manager holds the session" shape, and it is the reason
 * lifecycle handling here is only a few lines: there is no resume/pause plumbing
 * to get wrong, only *our* references to release when the session goes away.
 * See [release].
 *
 * ## Threading
 *
 * [onSessionUpdated] and [onTouchEvent] are called by SceneView on the main
 * thread (its render loop is Choreographer-driven), which is also where snapshot
 * state may be written and where Filament's JNI requires calls to happen. Nothing
 * here is safe to call from a background thread.
 *
 * Create one with [rememberArSessionManager] and pass it to [ArScene].
 */
@Stable
class ARSessionManager internal constructor(
    /** Camera permission, requested at runtime. [ArScene] renders nothing until granted. */
    val permission: ArCameraPermission,
    /**
     * Movement, in pixels, beyond which a touch is a drag rather than a tap.
     * Supplied from `LocalViewConfiguration` so it tracks the device's density
     * instead of guessing.
     */
    private val touchSlopPx: Float,
) {

    private val _placements = mutableStateListOf<ArPlacement>()

    /** Everything currently anchored, oldest first. Observable from composition. */
    val placements: List<ArPlacement> get() = _placements

    /** The live session, or `null` before creation and after destruction. */
    var session: Session? by mutableStateOf(null)
        private set

    /** True between session resume and pause. */
    var isSessionRunning: Boolean by mutableStateOf(false)
        private set

    /** Why tracking is degraded right now, or `null` when tracking is healthy. */
    var trackingFailure: TrackingFailureReason? by mutableStateOf(null)
        private set

    /**
     * True once ARCore has found at least one plane, i.e. once the translucent
     * plane grid is on screen and tapping can succeed. Drives the "point your
     * camera at the floor" hint; it deliberately latches, because the hint has
     * done its job the moment the first plane appears.
     */
    var hasTrackedPlane: Boolean by mutableStateOf(false)
        private set

    /** The model a plane tap places. Change it to switch what the next tap drops. */
    @get:RawRes
    var activeModelRes: Int by mutableStateOf(ArModels.PLACEHOLDER_CUBE)

    /** Longest edge, in metres, that placed models are scaled to. */
    var activeScaleToUnits: Float by mutableStateOf(ArModels.DEFAULT_SCALE_METRES)

    /** Whether obstacle and uneven surface detection is active. */
    var isObstacleDetectionEnabled: Boolean by mutableStateOf(true)
        private set

    private val _detectedObstacles = mutableStateListOf<ArObstacle>()
    val detectedObstacles: List<ArObstacle> get() = _detectedObstacles

    private var lastObstacleScanTimeMs = 0L

    /**
     * Latest view and projection matrices, column-major, as ARCore supplies them.
     * Kept for training modules that need to project a world pose to screen space
     * themselves — ARCore has no API to ask "where is this pose on screen". The
     * obstacle silhouettes do not use these; depth contours arrive already in view
     * coordinates. Written every frame on the GL thread, read on the UI thread — a
     * Compose snapshot state holder makes that read coherent.
     */
    var cameraMatrices: ArCameraMatrices? by mutableStateOf(null)
        private set

    private val viewMatrixBuf = FloatArray(16)
    private val projMatrixBuf = FloatArray(16)

    /**
     * Traced silhouettes of objects standing on the floor, view-normalized.
     *
     * Empty when the device has no depth support or depth has not warmed up yet.
     * [detectedObstacles] still populates in that case and drives the warning chip
     * and proximity checks, but nothing is drawn — see [ObstacleHighlightOverlay].
     */
    var obstacleContours: List<ObstacleContour> by mutableStateOf(emptyList())
        private set

    /** True once a depth frame has been successfully read at least once. */
    var hasDepth: Boolean by mutableStateOf(false)
        private set

    private var lastContourScanTimeMs = 0L

    /**
     * Frames the contour set has been empty for. Silhouettes flicker out for a
     * frame or two when depth confidence dips, and clearing on the first empty
     * result makes the outline strobe. Three frames of agreement before clearing.
     */
    private var emptyContourFrames = 0

    fun toggleObstacleDetection(enabled: Boolean) {
        isObstacleDetectionEnabled = enabled
        if (!enabled) _detectedObstacles.clear()
    }

    /** Returns an obstacle near [hitResult] if any, or `null`. */
    fun isObstacleNear(hitResult: HitResult, marginMetres: Float = 0.35f): ArObstacle? {
        if (!isObstacleDetectionEnabled) return null
        val target = hitResult.hitPose
        return _detectedObstacles.firstOrNull { obstacle ->
            val dx = target.tx() - obstacle.x
            val dy = target.ty() - obstacle.y
            val dz = target.tz() - obstacle.z
            sqrt(dx * dx + dy * dy + dz * dz) <= (obstacle.radiusMetres + marginMetres)
        }
    }

    /** Currently selected placement for size and direction adjustment, or `null`. */
    var selectedPlacement: ArPlacement? by mutableStateOf(null)
        private set

    /** Selects or deselects a placement. Pass `null` to clear selection. */
    fun selectPlacement(placement: ArPlacement?) {
        selectedPlacement = placement
    }

    /**
     * What a confirmed tap on a horizontal plane does. Defaults to selecting an
     * existing object near the tap or placing [activeModelRes]. Replace it when a
     * module needs custom tap handling.
     *
     * Snapshot-backed like the rest of the public surface, so this class can
     * honestly claim [Stable].
     */
    var onPlaneTap: (HitResult) -> Unit by mutableStateOf(
        value = { hit: HitResult ->
            val existing = placementNear(hit)
            if (existing != null) {
                selectPlacement(existing)
            } else {
                placeObjectAt(hit, activeModelRes, activeScaleToUnits)
            }
        }
    )

    private var nextPlacementId = 0L

    /**
     * Camera pose from the most recent frame — the origin of a tap ray. Kept off
     * the snapshot system on purpose: it changes every frame, nothing observes it,
     * and recomposing 60 times a second to track the camera would be absurd.
     */
    private var lastCameraPose: Pose? = null

    // Tap gesture state. MotionEvent instances are recycled, so only primitives
    // are retained.
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var couldBeTap = false

    /**
     * Taps recorded but not yet hit-tested. A hit test needs a [Frame], which
     * only exists inside [onSessionUpdated], so touches are queued for one frame
     * rather than resolved where they arrive.
     */
    private val pendingTaps = ArrayDeque<Tap>()

    private class Tap(val x: Float, val y: Float)

    /**
     * Enqueues a tap at screen pixel coordinates [x], [y] to be hit-tested on the next frame.
     * Useful for UI buttons like "Add Object at Center".
     */
    fun enqueueTap(x: Float, y: Float) {
        if (pendingTaps.size >= MAX_PENDING_TAPS) pendingTaps.removeFirstOrNull()
        pendingTaps.addLast(Tap(x, y))
    }

    /**
     * Anchors [modelRes] at [hitResult] and returns the placement, or `null` if
     * ARCore refused to create the anchor (which it does when the session is not
     * tracking, or when the anchor budget is exhausted).
     *
     * @param scaleToUnits longest edge of the model in metres.
     * @param tag caller's identifier for what the model represents; see
     *   [ArPlacement.tag] and [placementNear].
     */
    fun placeObjectAt(
        hitResult: HitResult,
        @RawRes modelRes: Int,
        scaleToUnits: Float = ArModels.DEFAULT_SCALE_METRES,
        tag: Any? = null,
        initialRotationY: Float = 0f,
        initialFloatHeight: Float = if (isExtinguisherModel(modelRes)) ArModels.EXTINGUISHER_DEFAULT_FLOAT_HEIGHT_METRES else 0f,
        autoSelect: Boolean = false,
    ): ArPlacement? {
        val anchor = try {
            hitResult.createAnchor()
        } catch (e: Exception) {
            // Session not tracking, session paused, or trackable no longer valid.
            Log.w(TAG, "createAnchor failed", e)
            return null
        }

        // Anchors are not free — each one is pose-tracked every frame. Evict the
        // oldest rather than letting a trainee tap the scene into a slideshow.
        while (_placements.size >= MAX_PLACEMENTS) {
            remove(_placements.first())
        }

        val placement = ArPlacement(
            id = nextPlacementId++,
            anchor = anchor,
            modelRes = modelRes,
            scaleToUnits = scaleToUnits,
            tag = tag,
            initialRotationY = initialRotationY,
            initialFloatHeight = initialFloatHeight,
        )
        _placements += placement
        if (autoSelect) {
            selectedPlacement = placement
        }
        return placement
    }

    private fun isExtinguisherModel(@RawRes modelRes: Int): Boolean {
        return modelRes == ArModels.EXTINGUISHER_REALISTIC ||
            modelRes == ArModels.EXTINGUISHER_CO2 ||
            modelRes == ArModels.EXTINGUISHER_FOAM ||
            modelRes == ArModels.EXTINGUISHER_WATER
    }

    /**
     * The placement the trainee most plausibly meant to tap, or `null` if the tap
     * was not near anything.
     *
     * ## Why this is not a real object pick
     *
     * ARCore hit tests hit *trackables* — planes and points the session has
     * mapped — and know nothing about rendered geometry. So a tap on an
     * extinguisher does not report the extinguisher; it reports the patch of floor
     * the ray reaches after passing through it. SceneView can pick Filament nodes
     * (that is the `HitResult` its `onTouchEvent` hands over), but mapping a node
     * back to an [ArPlacement] means holding node references this class has no
     * reason to own.
     *
     * Instead: take the segment from the camera to the reported floor point, and
     * find the anchor whose position comes closest to it. A tap anywhere on a
     * waist-high object puts that segment within about 0.25 m of the object's
     * base, comfortably inside [SELECT_RADIUS_METRES], while the floor between
     * objects matches nothing. Nearest wins, so overlapping radii still resolve to
     * the closer object — and [isCrowded] stops objects being placed close enough
     * together for that to be a coin flip in the first place.
     *
     * Falls back to plain distance from [hitResult] if no camera pose has been
     * seen yet, which only happens before the first frame.
     */
    fun placementNear(
        hitResult: HitResult,
        radiusMetres: Float = SELECT_RADIUS_METRES,
    ): ArPlacement? {
        val target = hitResult.hitPose
        val origin = lastCameraPose

        var best: ArPlacement? = null
        var bestDistance = radiusMetres

        _placements.forEach { placement ->
            val anchor = placement.anchor
            // A lost anchor has a stale pose; picking it would be picking a ghost.
            if (anchor.trackingState != TrackingState.TRACKING) return@forEach
            val pose = anchor.pose
            val distance = if (origin == null) {
                distanceBetween(pose, target)
            } else {
                distanceToSegment(pose, origin, target)
            }
            if (distance <= bestDistance) {
                bestDistance = distance
                best = placement
            }
        }
        return best
    }

    /**
     * True if [hitResult] lands close enough to something already placed that the
     * two would be hard to tell apart.
     *
     * [placementNear] resolves a tap to the *nearest* anchor, which is only a
     * meaningful answer when objects are further apart than the radius it searches.
     * Rather than document that as a warning and hope trainees spread things out,
     * placement steps call this and refuse the tap. [MIN_SEPARATION_METRES] is
     * comfortably wider than [SELECT_RADIUS_METRES] for exactly that reason.
     *
     * Distance is point-to-point here, not ray-to-point: this asks "is that spot
     * already taken", which is a question about the world, not about the tap.
     */
    fun isCrowded(
        hitResult: HitResult,
        radiusMetres: Float = MIN_SEPARATION_METRES,
    ): Boolean {
        val target = hitResult.hitPose
        return _placements.any { placement ->
            val anchor = placement.anchor
            anchor.trackingState == TrackingState.TRACKING &&
                distanceBetween(anchor.pose, target) <= radiusMetres
        }
    }

    /**
     * Distance in metres between two placements, or `null` if either anchor has
     * stopped tracking — a lost anchor's pose is stale, and a measurement against
     * it would be fiction.
     */
    fun distanceBetweenPlacements(a: ArPlacement, b: ArPlacement): Float? {
        if (a.anchor.trackingState != TrackingState.TRACKING) return null
        if (b.anchor.trackingState != TrackingState.TRACKING) return null
        return distanceBetween(a.anchor.pose, b.anchor.pose)
    }

    /** Detaches [placement]'s anchor and stops rendering it. */
    fun remove(placement: ArPlacement) {
        if (selectedPlacement == placement) {
            selectedPlacement = null
        }
        if (_placements.remove(placement)) placement.detachAnchor()
    }

    /** Detaches every anchor. Safe to call repeatedly. */
    fun clear() {
        selectedPlacement = null
        val detaching = _placements.toList()
        _placements.clear()
        detaching.forEach { it.detachAnchor() }
    }

    /**
     * Records touches so [onSessionUpdated] can hit-test them.
     *
     * Always returns `false`: we observe the gesture, we do not consume it, so
     * SceneView's own gesture detectors keep working for node dragging later.
     *
     * Note that `ARSceneView.onTouchEvent` hands us an
     * `io.github.sceneview.collision.HitResult` — a Filament *node* pick, not an
     * ARCore plane hit — which is why this takes only the [MotionEvent] and does
     * its own [Frame.hitTest] against real trackables.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTimeMs = event.eventTime
                couldBeTap = true
            }

            MotionEvent.ACTION_MOVE ->
                if (couldBeTap && hypot(event.x - downX, event.y - downY) > touchSlopPx) {
                    couldBeTap = false
                }

            MotionEvent.ACTION_UP -> {
                if (couldBeTap && event.eventTime - downTimeMs <= TAP_TIMEOUT_MS) {
                    if (pendingTaps.size >= MAX_PENDING_TAPS) pendingTaps.removeFirstOrNull()
                    pendingTaps.addLast(Tap(event.x, event.y))
                }
                couldBeTap = false
            }

            // A second finger means pinch or rotate, never a placement.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> couldBeTap = false
        }
        return false
    }

    internal fun onSessionCreated(session: Session) {
        adopt(session)
    }

    internal fun onSessionResumed(session: Session) {
        adopt(session)
        isSessionRunning = true
    }

    internal fun onSessionPaused(session: Session) {
        isSessionRunning = false
        // Placements are kept: anchors survive a pause and come back with
        // tracking on resume. They are only dropped when the session itself is
        // replaced (see adopt) or the screen leaves (see release).
        pendingTaps.clear()
        couldBeTap = false
    }

    internal fun onTrackingFailureChanged(reason: TrackingFailureReason?) {
        trackingFailure = reason
    }

    internal fun onSessionUpdated(session: Session, frame: Frame) {
        // Backstop: if SceneView ever hands us a session we were not told about,
        // adopt it here rather than rendering anchors that belong to a dead one.
        if (this.session !== session) adopt(session)
        isSessionRunning = true

        val camera = frame.camera
        lastCameraPose = camera.pose

        if (!hasTrackedPlane) {
            hasTrackedPlane = session.getAllTrackables(Plane::class.java)
                .any { it.trackingState == TrackingState.TRACKING }
        }

        // Near plane 0.1 m: obstacles can legitimately be under the phone when a
        // trainee steps over one, and a larger near plane clips the ring away
        // exactly when the warning matters most.
        camera.getViewMatrix(viewMatrixBuf, 0)
        camera.getProjectionMatrix(projMatrixBuf, 0, 0.1f, 30f)
        cameraMatrices = ArCameraMatrices(
            view = viewMatrixBuf.copyOf(),
            projection = projMatrixBuf.copyOf(),
        )

        if (isObstacleDetectionEnabled && SystemClock.uptimeMillis() - lastObstacleScanTimeMs > 600L) {
            lastObstacleScanTimeMs = SystemClock.uptimeMillis()
            scanForSurfaceObstacles(session, frame)
        }

        // Silhouettes run on their own faster clock (~7 Hz): they are screen-space
        // and visibly lag the camera if refreshed at the 600 ms obstacle cadence.
        if (isObstacleDetectionEnabled &&
            SystemClock.uptimeMillis() - lastContourScanTimeMs > 140L
        ) {
            lastContourScanTimeMs = SystemClock.uptimeMillis()
            scanForContours(session, frame)
        }

        while (true) {
            val tap = pendingTaps.removeFirstOrNull() ?: break
            horizontalPlaneHit(frame, tap)?.let(onPlaneTap)
        }
    }

    private fun scanForContours(session: Session, frame: Frame) {
        // Lowest tracked horizontal plane, not the first: ARCore often locks onto a
        // table before the floor, and measuring height above a tabletop makes the
        // floor itself register as a metre-tall obstacle.
        val floorY = session.getAllTrackables(Plane::class.java)
            .filter {
                it.trackingState == TrackingState.TRACKING &&
                    it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            }
            .minOfOrNull { it.centerPose.ty() } ?: return

        val contours = DepthObstacleMask.extract(frame, floorY)
        if (contours.isNotEmpty()) {
            hasDepth = true
            emptyContourFrames = 0
            obstacleContours = contours
        } else if (obstacleContours.isNotEmpty()) {
            emptyContourFrames++
            if (emptyContourFrames >= 3) {
                obstacleContours = emptyList()
                emptyContourFrames = 0
            }
        }
    }

    private fun scanForSurfaceObstacles(session: Session, frame: Frame) {
        val planes = session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        if (planes.isEmpty()) return

        val primaryPlane = planes.first()
        val floorY = primaryPlane.centerPose.ty()

        val pointCloud = try {
            frame.acquirePointCloud()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val points: FloatBuffer = pointCloud.points
            val numPoints = points.remaining() / 4
            if (numPoints <= 0) return

            val candidatePoints = ArrayList<Triple<Float, Float, Float>>()
            val heightByCandidate = ArrayList<Float>()

            for (i in 0 until numPoints) {
                val px = points.get(i * 4 + 0)
                val py = points.get(i * 4 + 1)
                val pz = points.get(i * 4 + 2)
                val confidence = points.get(i * 4 + 3)

                val heightAboveFloor = py - floorY
                if (confidence > 0.3f && heightAboveFloor in 0.08f..0.50f) {
                    candidatePoints.add(Triple(px, py, pz))
                    heightByCandidate.add(heightAboveFloor)
                }
            }

            if (candidatePoints.isNotEmpty()) {
                val clusters = clusterPoints(candidatePoints, minDistanceMetres = 0.5f)
                var obstacleId = 0L
                val newObstacles = clusters.take(6).map { (cx, cy, cz) ->
                    // Cluster extent in the floor plane sets the ring radius, so a
                    // wide slab of debris gets a wide outline instead of a fixed
                    // 0.35 m circle that under-reports its footprint. Points within
                    // one cluster spacing of the centroid are treated as members.
                    var maxRadial = 0f
                    var peak = 0.08f
                    candidatePoints.forEachIndexed { idx, (px, py, pz) ->
                        val radial = hypot(px - cx, pz - cz)
                        if (radial <= 0.5f) {
                            if (radial > maxRadial) maxRadial = radial
                            val h = heightByCandidate[idx]
                            if (h > peak) peak = h
                        }
                    }
                    ArObstacle(
                        id = ++obstacleId,
                        x = cx,
                        y = cy,
                        z = cz,
                        radiusMetres = maxRadial.coerceIn(0.18f, 0.60f),
                        description = when {
                            peak >= 0.35f -> "Raised Obstruction — Step Around"
                            peak >= 0.18f -> "Rock / Debris — Trip Hazard"
                            else -> "Uneven Surface"
                        },
                        floorY = floorY,
                        heightMetres = peak,
                    )
                }
                _detectedObstacles.clear()
                _detectedObstacles.addAll(newObstacles)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Obstacle detection scan failed", e)
        } finally {
            pointCloud.close()
        }
    }

    private fun clusterPoints(
        points: List<Triple<Float, Float, Float>>,
        minDistanceMetres: Float,
    ): List<Triple<Float, Float, Float>> {
        val clusters = mutableListOf<Triple<Float, Float, Float>>()
        for (point in points) {
            val exists = clusters.any { (cx, cy, cz) ->
                val dx = point.first - cx
                val dy = point.second - cy
                val dz = point.third - cz
                sqrt(dx * dx + dy * dy + dz * dz) < minDistanceMetres
            }
            if (!exists) {
                clusters.add(point)
            }
        }
        return clusters
    }

    /**
     * Releases everything this holder owns. Called when the composition leaves;
     * anchors are the leak that matters, because they keep native ARCore
     * allocations alive for as long as the session does.
     */
    internal fun release() {
        clear()
        pendingTaps.clear()
        couldBeTap = false
        lastCameraPose = null
        session = null
        isSessionRunning = false
        trackingFailure = null
        hasTrackedPlane = false
    }

    /**
     * Nearest hit on a floor-like plane, or `null`.
     *
     * Three filters matter. [Plane.Type.HORIZONTAL_UPWARD_FACING] excludes
     * ceilings and walls; [Plane.isPoseInPolygon] excludes the infinite plane
     * outside the polygon ARCore has actually observed, which is what stops
     * objects landing in mid-air past the edge of a table; and the tracking-state
     * check excludes planes that have been subsumed by a larger one.
     */
    private fun horizontalPlaneHit(frame: Frame, tap: Tap): HitResult? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null
        return try {
            // hitTest returns hits sorted nearest-first.
            frame.hitTest(tap.x, tap.y).firstOrNull { hit ->
                val trackable = hit.trackable
                trackable is Plane &&
                    trackable.trackingState == TrackingState.TRACKING &&
                    trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    trackable.isPoseInPolygon(hit.hitPose)
            }
        } catch (e: Exception) {
            // Never let a bad frame throw out of the render loop.
            Log.w(TAG, "hitTest failed", e)
            null
        }
    }

    private fun adopt(session: Session) {
        if (this.session === session) return
        // A different session means every existing anchor belongs to a session
        // that is being torn down; keeping them would render stale poses.
        clear()
        this.session = session
        trackingFailure = null
        hasTrackedPlane = false
    }

    private fun ArPlacement.detachAnchor() {
        try {
            anchor.detach()
        } catch (e: Exception) {
            // Detaching an anchor whose session is already destroyed is a no-op
            // we do not care about, and not worth crashing a training session for.
            Log.w(TAG, "anchor.detach failed", e)
        }
    }

    private companion object {
        const val TAG = "ARSessionManager"

        /** Long-press timeout: anything slower than this is not a tap. */
        const val TAP_TIMEOUT_MS = 500L

        /** ARCore degrades well before this; the cap is a guard, not a target. */
        const val MAX_PLACEMENTS = 20

        const val MAX_PENDING_TAPS = 4

        /**
         * How far a tap ray may pass from an anchor and still count as picking it.
         *
         * 0.5 m is derived, not guessed: for a 0.55 m object viewed from a normal
         * standing distance, a tap on the very top of it puts the camera-to-floor
         * segment about 0.45 m from the object's base anchor. Tighter than this and
         * trainees find that tapping the top of an extinguisher does nothing.
         */
        const val SELECT_RADIUS_METRES = 0.5f

        /**
         * Closest two placements may be. Wider than [SELECT_RADIUS_METRES] so that
         * the nearest anchor to a tap ray is unambiguously the one aimed at. See
         * [isCrowded].
         */
        const val MIN_SEPARATION_METRES = 0.9f
    }
}

/** Straight-line distance between two poses' translations, in metres. */
private fun distanceBetween(a: Pose, b: Pose): Float =
    length(a.tx() - b.tx(), a.ty() - b.ty(), a.tz() - b.tz())

/**
 * Distance from [point] to the segment [start]..[end], in metres.
 *
 * A segment rather than an infinite ray so that objects behind the camera, or
 * beyond the surface that was actually tapped, cannot be picked.
 */
private fun distanceToSegment(point: Pose, start: Pose, end: Pose): Float {
    val dx = end.tx() - start.tx()
    val dy = end.ty() - start.ty()
    val dz = end.tz() - start.tz()
    val lengthSquared = dx * dx + dy * dy + dz * dz
    if (lengthSquared <= 0f) return distanceBetween(point, start)

    val wx = point.tx() - start.tx()
    val wy = point.ty() - start.ty()
    val wz = point.tz() - start.tz()
    // Projection of w onto d, clamped to the segment.
    val t = ((wx * dx + wy * dy + wz * dz) / lengthSquared).coerceIn(0f, 1f)

    return length(wx - t * dx, wy - t * dy, wz - t * dz)
}

private fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

/**
 * Creates an [ARSessionManager] scoped to the current composition, requests the
 * camera permission, and releases anchors when the composition leaves.
 *
 * Surviving rotation is a manifest concern, not a Compose one: `ARTestActivity`
 * declares `android:configChanges="orientation|screenSize|..."` so the activity
 * is not recreated and the session, the manager and every placement stay put. If
 * the activity *is* recreated — process death, or a config change outside that
 * list — placements are lost, because an ARCore [Anchor] cannot be serialised.
 * Persisting placements across process death needs ARCore Cloud Anchors.
 */
@Composable
fun rememberArSessionManager(
    permission: ArCameraPermission = rememberArCameraPermission(),
): ARSessionManager {
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val manager = remember(permission, touchSlopPx) {
        ARSessionManager(permission = permission, touchSlopPx = touchSlopPx)
    }
    DisposableEffect(manager) {
        onDispose { manager.release() }
    }
    return manager
}
