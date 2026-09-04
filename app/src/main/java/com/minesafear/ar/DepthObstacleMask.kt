package com.minesafear.ar

import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One obstacle traced as a closed silhouette.
 *
 * [points] are view-normalized — x and y in [0, 1], y increasing downward, i.e.
 * already in the same space Compose `Canvas` draws in once multiplied by size.
 * Stored flat (x0, y0, x1, y1, ...) because these are rebuilt several times a
 * second and a `List<Offset>` per vertex is pure allocation churn.
 */
class ObstacleContour(
    val id: Int,
    val points: FloatArray,
    val peakHeightMetres: Float,
    val nearestDistanceMetres: Float,
)

/**
 * Turns one ARCore depth frame into traced outlines of whatever is standing on
 * the floor.
 *
 * ## Why depth and not the point cloud
 *
 * `Frame.acquirePointCloud` returns a few hundred sparse feature points, which is
 * enough to know *that* something is there — that is what the old ring overlay
 * ran on — but far too sparse to know its shape. The depth image is a dense
 * per-pixel range map (roughly 160x120 on most devices), so an object's outline is
 * genuinely recoverable from it.
 *
 * ## Pipeline
 *
 * 1. Unproject every Nth depth pixel to a world point, using the camera's image
 *    intrinsics scaled to the depth image's smaller dimensions.
 * 2. Keep pixels whose height above the floor plane falls in an obstacle band —
 *    above ankle scuff, below head height. This is what separates obstacle from
 *    floor: the floor itself sits at height ~0 and drops out, and walls climb past
 *    the top of the band and drop out too.
 * 3. Flood-fill the kept cells into connected components, discarding specks.
 * 4. Trace each component's outer boundary (Moore-neighbour), simplify with
 *    Ramer-Douglas-Peucker, and map the surviving vertices through
 *    `Frame.transformCoordinates2d` into view space.
 *
 * Step 4 must use `transformCoordinates2d` rather than a manual scale: the depth
 * image is in sensor orientation and a different aspect ratio from the viewport,
 * so ARCore's transform is the only thing that gets rotation and centre-crop
 * right across devices.
 */
object DepthObstacleMask {

    private const val TAG = "DepthObstacleMask"

    /** Sample every Nth depth pixel. 2 keeps ~80x60 cells — plenty for a silhouette. */
    private const val STRIDE = 2

    /** Components smaller than this are sensor noise, not obstacles. */
    private const val MIN_CELLS = 30

    /** Obstacle height band above the floor plane, metres. */
    private const val MIN_HEIGHT_M = 0.06f
    private const val MAX_HEIGHT_M = 1.60f

    /** Usable depth range. Beyond ~6 m the depth map is too noisy to trust. */
    private const val MIN_DEPTH_M = 0.25f
    private const val MAX_DEPTH_M = 6.0f

    private const val MAX_CONTOURS = 5

    /** Simplification tolerance, in grid cells. Higher = blockier outline. */
    private const val RDP_EPSILON_CELLS = 1.2f

    /**
     * @param floorY world Y of the tracked floor plane.
     * @return contours in view-normalized coords, or empty if depth is unavailable
     *   this frame (which is normal for the first second, and permanent on devices
     *   without depth support — the caller should fall back to ring markers).
     */
    fun extract(frame: Frame, floorY: Float): List<ObstacleContour> {
        val depthImage = try {
            frame.acquireDepthImage16Bits()
        } catch (e: Throwable) {
            // NotYetAvailableException on early frames; UnsupportedOperation or
            // IllegalState when depthMode was never enabled. All non-fatal.
            null
        } ?: return emptyList()

        try {
            val dw = depthImage.width
            val dh = depthImage.height
            val plane = depthImage.planes[0]
            val buf = plane.buffer.order(ByteOrder.nativeOrder())
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // Intrinsics belong to the full-res camera image; scale them to the
            // depth image's grid. Focal length and principal point both scale
            // linearly with resolution.
            val intr = frame.camera.imageIntrinsics
            val focal = intr.focalLength
            val principal = intr.principalPoint
            val dims = intr.imageDimensions
            val sx = dw.toFloat() / dims[0]
            val sy = dh.toFloat() / dims[1]
            val fx = focal[0] * sx
            val fy = focal[1] * sy
            val cx = principal[0] * sx
            val cy = principal[1] * sy
            if (fx <= 0f || fy <= 0f) return emptyList()

            // Camera pose as a column-major matrix, applied by hand. Pose
            // .transformPoint allocates a FloatArray per call and this loop runs
            // thousands of times per scan.
            val pm = FloatArray(16)
            frame.camera.pose.toMatrix(pm, 0)

            val gw = dw / STRIDE
            val gh = dh / STRIDE
            if (gw < 4 || gh < 4) return emptyList()

            val mask = BooleanArray(gw * gh)
            val heights = FloatArray(gw * gh)
            val ranges = FloatArray(gw * gh)

            for (gy in 0 until gh) {
                val v = gy * STRIDE
                val rowBase = v * rowStride
                for (gx in 0 until gw) {
                    val u = gx * STRIDE
                    val idx = rowBase + u * pixelStride
                    if (idx + 1 >= buf.limit()) continue

                    // DEPTH16: low 13 bits are millimetres, high 3 bits confidence.
                    val raw = buf.getShort(idx).toInt() and 0xFFFF
                    val mm = raw and 0x1FFF
                    val confidence = (raw shr 13) and 0x7
                    if (mm == 0 || confidence == 0) continue

                    val d = mm / 1000f
                    if (d < MIN_DEPTH_M || d > MAX_DEPTH_M) continue

                    // Pinhole unprojection into ARCore camera space: the camera
                    // looks down -Z and its Y is up, while image v grows downward,
                    // hence the two sign flips.
                    val ex = (u - cx) * d / fx
                    val ey = -(v - cy) * d / fy
                    val ez = -d

                    // World Y only — the height test is all that is needed here, so
                    // skip the X and Z rows of the multiply entirely.
                    val wy = pm[1] * ex + pm[5] * ey + pm[9] * ez + pm[13]
                    val h = wy - floorY
                    if (h < MIN_HEIGHT_M || h > MAX_HEIGHT_M) continue

                    val g = gy * gw + gx
                    mask[g] = true
                    heights[g] = h
                    ranges[g] = d
                }
            }

            val components = labelComponents(mask, gw, gh)
            if (components.isEmpty()) return emptyList()

            // Nearest first: if there are more blobs than MAX_CONTOURS, the ones
            // about to be tripped over matter more than the ones across the room.
            val ordered = components
                .map { cells ->
                    var peak = 0f
                    var nearest = Float.MAX_VALUE
                    cells.forEach { g ->
                        if (heights[g] > peak) peak = heights[g]
                        if (ranges[g] < nearest) nearest = ranges[g]
                    }
                    Triple(cells, peak, nearest)
                }
                .sortedBy { it.third }
                .take(MAX_CONTOURS)

            val out = ArrayList<ObstacleContour>(ordered.size)
            var nextId = 0
            ordered.forEach { (cells, peak, nearest) ->
                val boundary = traceBoundary(cells, gw, gh) ?: return@forEach
                val simplified = rdpSimplify(boundary, RDP_EPSILON_CELLS)
                if (simplified.size < 3) return@forEach

                // Grid cell -> depth-image-normalized -> view-normalized. The +0.5
                // centres the sample inside its cell rather than on its corner.
                val src = FloatArray(simplified.size * 2)
                simplified.forEachIndexed { i, (gx, gy) ->
                    src[i * 2] = (gx + 0.5f) * STRIDE / dw
                    src[i * 2 + 1] = (gy + 0.5f) * STRIDE / dh
                }
                val dst = FloatArray(src.size)
                frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_NORMALIZED, src,
                    Coordinates2d.VIEW_NORMALIZED, dst,
                )
                // VIEW_NORMALIZED is [-1,1] with y up; Canvas wants [0,1] y down.
                for (i in dst.indices step 2) {
                    dst[i] = (dst[i] + 1f) * 0.5f
                    dst[i + 1] = (1f - dst[i + 1]) * 0.5f
                }

                out.add(
                    ObstacleContour(
                        id = nextId++,
                        points = dst,
                        peakHeightMetres = peak,
                        nearestDistanceMetres = nearest,
                    )
                )
            }
            return out
        } catch (e: Throwable) {
            Log.w(TAG, "depth mask failed", e)
            return emptyList()
        } finally {
            depthImage.close()
        }
    }

    /** 4-connected flood fill. Iterative: a recursive fill blows the stack at 80x60. */
    private fun labelComponents(mask: BooleanArray, gw: Int, gh: Int): List<IntArray> {
        val seen = BooleanArray(mask.size)
        val result = ArrayList<IntArray>()
        val stack = IntArray(mask.size)
        val cells = IntArray(mask.size)

        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            var count = 0
            stack[sp++] = start
            seen[start] = true

            while (sp > 0) {
                val g = stack[--sp]
                cells[count++] = g
                val gx = g % gw
                val gy = g / gw
                if (gx > 0) pushIf(g - 1, mask, seen, stack, sp).let { sp = it }
                if (gx < gw - 1) pushIf(g + 1, mask, seen, stack, sp).let { sp = it }
                if (gy > 0) pushIf(g - gw, mask, seen, stack, sp).let { sp = it }
                if (gy < gh - 1) pushIf(g + gw, mask, seen, stack, sp).let { sp = it }
            }
            if (count >= MIN_CELLS) result.add(cells.copyOf(count))
        }
        return result
    }

    private fun pushIf(
        g: Int,
        mask: BooleanArray,
        seen: BooleanArray,
        stack: IntArray,
        sp: Int,
    ): Int {
        if (mask[g] && !seen[g]) {
            seen[g] = true
            stack[sp] = g
            return sp + 1
        }
        return sp
    }

    /**
     * Moore-neighbour boundary tracing of one component's outer edge.
     *
     * Starts at the component's topmost-then-leftmost cell — guaranteed to be on
     * the outer boundary — and walks clockwise, each step resuming the 8-neighbour
     * search from just past where the previous step entered. Holes are ignored:
     * an outline is a silhouette, so interior gaps should not be drawn.
     */
    private fun traceBoundary(cells: IntArray, gw: Int, gh: Int): List<Pair<Int, Int>>? {
        val member = HashSet<Int>(cells.size * 2)
        cells.forEach { member.add(it) }

        val startG = cells.minByOrNull { (it / gw) * gw + (it % gw) } ?: return null
        val startX = startG % gw
        val startY = startG / gw

        // Clockwise from east.
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        val path = ArrayList<Pair<Int, Int>>()
        var cx = startX
        var cy = startY
        var dir = 0
        val maxSteps = cells.size * 8 + 32
        var steps = 0

        do {
            path.add(cx to cy)
            var found = false
            // Backtrack 2 slots so the search sweeps around the incoming edge.
            var k = (dir + 6) % 8
            for (i in 0 until 8) {
                val nd = (k + i) % 8
                val nx = cx + dx[nd]
                val ny = cy + dy[nd]
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                if (member.contains(ny * gw + nx)) {
                    cx = nx
                    cy = ny
                    dir = nd
                    found = true
                    break
                }
            }
            if (!found) break // isolated cell
            steps++
        } while ((cx != startX || cy != startY) && steps < maxSteps)

        return if (path.size >= 3) path else null
    }

    /** Ramer-Douglas-Peucker on a closed ring, iterative to avoid deep recursion. */
    private fun rdpSimplify(pts: List<Pair<Int, Int>>, epsilon: Float): List<Pair<Int, Int>> {
        if (pts.size < 4) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true

        val ranges = ArrayDeque<Pair<Int, Int>>()
        ranges.addLast(0 to pts.size - 1)

        while (ranges.isNotEmpty()) {
            val (a, b) = ranges.removeLast()
            if (b <= a + 1) continue
            var worst = 0f
            var worstIdx = -1
            for (i in a + 1 until b) {
                val d = perpendicularDistance(pts[i], pts[a], pts[b])
                if (d > worst) {
                    worst = d
                    worstIdx = i
                }
            }
            if (worstIdx > 0 && worst > epsilon) {
                keep[worstIdx] = true
                ranges.addLast(a to worstIdx)
                ranges.addLast(worstIdx to b)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicularDistance(
        p: Pair<Int, Int>,
        a: Pair<Int, Int>,
        b: Pair<Int, Int>,
    ): Float {
        val ax = a.first.toFloat()
        val ay = a.second.toFloat()
        val bx = b.first.toFloat()
        val by = b.second.toFloat()
        val px = p.first.toFloat()
        val py = p.second.toFloat()
        val len = hypot(bx - ax, by - ay)
        // Degenerate segment: fall back to point distance from the endpoint.
        if (len < 1e-4f) return hypot(px - ax, py - ay)
        return abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / len
    }

    /** Axis-aligned bounds of a contour, view-normalized. Used to place its label. */
    fun boundsOf(contour: ObstacleContour): FloatArray {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val p = contour.points
        for (i in p.indices step 2) {
            minX = min(minX, p[i]); maxX = max(maxX, p[i])
            minY = min(minY, p[i + 1]); maxY = max(maxY, p[i + 1])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
