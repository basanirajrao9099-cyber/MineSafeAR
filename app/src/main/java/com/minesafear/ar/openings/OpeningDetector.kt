package com.minesafear.ar.openings

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A detected opening in analysis-image pixel coords. Corners are ordered TL, TR, BR, BL. */
data class OpeningCandidate(
    val corners: Array<Point>,
    val kind: OpeningKind,
    val score: Float,
    val edgeSupport: Float,
    val interiorDelta: Float,     // signed mean-intensity difference interior - surround, /255
    val bottomBelowHorizon: Boolean
) {
    val centre: Point get() = Point(corners.sumOf { it.x } / 4.0, corners.sumOf { it.y } / 4.0)
    val bboxWidth: Double get() = corners.maxOf { it.x } - corners.minOf { it.x }
    val bboxHeight: Double get() = corners.maxOf { it.y } - corners.minOf { it.y }
    fun bottomCentre() = Point((corners[2].x + corners[3].x) / 2.0, (corners[2].y + corners[3].y) / 2.0)
    fun topCentre() = Point((corners[0].x + corners[1].x) / 2.0, (corners[0].y + corners[1].y) / 2.0)

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

private class Seg(var x1: Float, var y1: Float, var x2: Float, var y2: Float) {
    val dx get() = x2 - x1
    val dy get() = y2 - y1
    val len get() = hypot(dx, dy)
    fun dirUnit(): FloatArray { val l = len.takeIf { it > 1e-3f } ?: 1f; return floatArrayOf(dx / l, dy / l) }
    fun midX() = (x1 + x2) / 2f
    fun midY() = (y1 + y2) / 2f
}

/**
 * Markerless opening detector. Uses gravity to rectify line orientation instead of assuming the
 * phone is upright, so "vertical" means "parallel to gravity in the image", which is what makes
 * door jambs separable from gallery-roof edges when the operator tilts the handset.
 *
 * Deliberately classical: no TFLite model to train, no per-device calibration profile, so it works
 * on the uncertified handsets where ARCore refuses to create a Session.
 */
class OpeningDetector(
    private val workWidth: Int = 480,
    private val minScore: Float = 0.46f
) {
    private val gray = Mat()
    private val blur = Mat()
    private val edges = Mat()
    private val linesMat = Mat()

    /**
     * @param inputGray single-channel Mat already rotated to display orientation
     * @param intr intrinsics matching inputGray
     * @param g unit gravity in that image's camera frame
     */
    fun detect(inputGray: Mat, intr: Intrinsics, g: FloatArray): List<OpeningCandidate> {
        // Reject frames rolled so far that the Manhattan assumption stops paying for itself.
        if (abs(GroundPlane.rollRad(g)) > 0.62f) return emptyList()

        val s = workWidth.toFloat() / inputGray.width()
        if (s < 1f) Imgproc.resize(inputGray, gray, Size(), s.toDouble(), s.toDouble(), Imgproc.INTER_AREA)
        else inputGray.copyTo(gray)
        val k = intr.scaled(min(s, 1f))
        val w = gray.width(); val h = gray.height()

        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        // Median-relative Canny thresholds: mine galleries and lit corridors differ by >3 stops.
        val median = medianOf(blur)
        val lo = max(20.0, 0.62 * median); val hi = min(240.0, 1.55 * median)
        Imgproc.Canny(blur, edges, lo, hi)

        Imgproc.HoughLinesP(
            edges, linesMat, 1.0, Math.PI / 180.0, 38,
            (0.10 * h), 14.0
        )
        if (linesMat.rows() < 4) return emptyList()

        val up = GroundPlane.imageUp(g)
        val verts = ArrayList<Seg>(); val horz = ArrayList<Seg>()
        for (i in 0 until linesMat.rows()) {
            val v = linesMat.get(i, 0) ?: continue
            val seg = Seg(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat())
            val d = seg.dirUnit()
            val alignUp = abs(d[0] * up[0] + d[1] * up[1])   // 1 = parallel to gravity
            when {
                alignUp > 0.966f -> {                        // within 15 deg of vertical
                    if (seg.y1 > seg.y2) { // canonical: y1 is top
                        val tx = seg.x1; val ty = seg.y1; seg.x1 = seg.x2; seg.y1 = seg.y2; seg.x2 = tx; seg.y2 = ty
                    }
                    verts += seg
                }
                alignUp < 0.30f -> {                         // within ~17 deg of horizontal
                    if (seg.x1 > seg.x2) {
                        val tx = seg.x1; val ty = seg.y1; seg.x1 = seg.x2; seg.y1 = seg.y2; seg.x2 = tx; seg.y2 = ty
                    }
                    horz += seg
                }
            }
        }
        val vs = mergeCollinear(verts, vertical = true, tol = 7f)
            .filter { it.len > 0.16f * h }
            .sortedBy { it.midX() }
        val hs = mergeCollinear(horz, vertical = false, tol = 7f).filter { it.len > 0.10f * w }
        if (vs.size < 2 || hs.size < 2) return emptyList()

        val out = ArrayList<OpeningCandidate>()
        for (i in vs.indices) for (j in i + 1 until vs.size) {
            val a = vs[i]; val b = vs[j]
            val gapPx = b.midX() - a.midX()
            if (gapPx < 0.055f * w || gapPx > 0.92f * w) continue
            // Jambs must share a vertical band, else they belong to different structures.
            val ovTop = max(a.y1, b.y1); val ovBot = min(a.y2, b.y2)
            val ov = ovBot - ovTop
            if (ov < 0.55f * min(a.len, b.len)) continue

            val top = pickRail(hs, a, b, ovTop, ovBot, wantTop = true) ?: continue
            val bot = pickRail(hs, a, b, ovTop, ovBot, wantTop = false) ?: continue
            val quad = quadFrom(a, b, top, bot) ?: continue
            if (!quadSane(quad, w, h)) continue

            val support = edgeSupport(quad, a, b, top, bot)
            if (support < 0.55f) continue
            val (delta, innerSd) = interiorStats(gray, quad)
            val horizon = GroundPlane.horizonY(k, g, quad.let { (it[2].x + it[3].x).toFloat() / 2f })
            val bottomY = (quad[2].y + quad[3].y) / 2.0
            val belowHorizon = horizon == null || bottomY > horizon

            val aspect = aspectOf(quad)
            val kind = classify(aspect, delta, innerSd, belowHorizon)
            if (kind == OpeningKind.UNKNOWN) continue

            val prior = SizePriors.of(kind)
            val aspectFit = 1f - (clampDist(aspect, prior.minAspect, prior.maxAspect) /
                    max(0.35f, prior.maxAspect - prior.minAspect)).coerceIn(0f, 1f)
            // Openings read as strong intensity steps; closed leaves read as weak steps with texture.
            val contrastFit = when (kind) {
                OpeningKind.DOORWAY -> (abs(delta) / 0.28f).coerceIn(0f, 1f)
                else -> (1f - abs(delta) / 0.45f).coerceIn(0f, 1f)
            }
            val geomFit = if (belowHorizon || kind == OpeningKind.WINDOW) 1f else 0.35f

            val score = 0.34f * support + 0.28f * aspectFit + 0.20f * contrastFit + 0.18f * geomFit
            if (score < minScore) continue

            // Report in input-image coords, not work-resolution coords.
            val inv = 1.0 / min(s, 1f)
            out += OpeningCandidate(
                corners = Array(4) { Point(quad[it].x * inv, quad[it].y * inv) },
                kind = kind, score = score, edgeSupport = support,
                interiorDelta = delta, bottomBelowHorizon = belowHorizon
            )
        }
        return nms(out, iouThresh = 0.32f).take(6)
    }

    fun release() = listOf(gray, blur, edges, linesMat).forEach { it.release() }

    // ---- helpers -------------------------------------------------------------------------------

    private fun medianOf(m: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(m), org.opencv.core.MatOfInt(0), Mat(),
            hist, org.opencv.core.MatOfInt(64), org.opencv.core.MatOfFloat(0f, 256f))
        val total = m.total().toDouble(); var acc = 0.0
        for (i in 0 until 64) { acc += hist.get(i, 0)[0]; if (acc >= total / 2.0) { hist.release(); return i * 4.0 + 2.0 } }
        hist.release(); return 128.0
    }

    /** Merge segments lying on the same infinite line so a jamb broken by occlusion stays one jamb. */
    private fun mergeCollinear(src: List<Seg>, vertical: Boolean, tol: Float): List<Seg> {
        val used = BooleanArray(src.size); val out = ArrayList<Seg>()
        for (i in src.indices) {
            if (used[i]) continue
            val acc = Seg(src[i].x1, src[i].y1, src[i].x2, src[i].y2); used[i] = true
            for (j in i + 1 until src.size) {
                if (used[j]) continue
                val ok = if (vertical) abs(acc.midX() - src[j].midX()) < tol &&
                        abs(acc.dirUnit()[0] - src[j].dirUnit()[0]) < 0.14f
                else abs(acc.midY() - src[j].midY()) < tol &&
                        abs(acc.dirUnit()[1] - src[j].dirUnit()[1]) < 0.14f
                if (!ok) continue
                used[j] = true
                if (vertical) {
                    if (src[j].y1 < acc.y1) { acc.y1 = src[j].y1; acc.x1 = src[j].x1 }
                    if (src[j].y2 > acc.y2) { acc.y2 = src[j].y2; acc.x2 = src[j].x2 }
                } else {
                    if (src[j].x1 < acc.x1) { acc.x1 = src[j].x1; acc.y1 = src[j].y1 }
                    if (src[j].x2 > acc.x2) { acc.x2 = src[j].x2; acc.y2 = src[j].y2 }
                }
            }
            out += acc
        }
        return out
    }

    /** Best horizontal rail spanning the two jambs: prefer extreme y, require 62% x-coverage. */
    private fun pickRail(hs: List<Seg>, a: Seg, b: Seg, ovTop: Float, ovBot: Float, wantTop: Boolean): Seg? {
        val xL = min(a.midX(), b.midX()); val xR = max(a.midX(), b.midX())
        val span = xR - xL
        val band = 0.22f * (ovBot - ovTop)
        var best: Seg? = null
        for (s in hs) {
            val cov = (min(s.x2, xR) - max(s.x1, xL)) / span
            if (cov < 0.62f) continue
            val y = s.midY()
            if (y < ovTop - band || y > ovBot + band) continue
            if (best == null || (wantTop && y < best.midY()) || (!wantTop && y > best.midY())) best = s
        }
        return best
    }

    /** Corners as the four line-line intersections, so genuine perspective quads survive. */
    private fun quadFrom(a: Seg, b: Seg, top: Seg, bot: Seg): Array<Point>? {
        val tl = inter(a, top) ?: return null
        val tr = inter(b, top) ?: return null
        val br = inter(b, bot) ?: return null
        val bl = inter(a, bot) ?: return null
        return arrayOf(tl, tr, br, bl)
    }

    private fun inter(p: Seg, q: Seg): Point? {
        val d = p.dx * q.dy - p.dy * q.dx
        if (abs(d) < 1e-4f) return null
        val t = ((q.x1 - p.x1) * q.dy - (q.y1 - p.y1) * q.dx) / d
        return Point((p.x1 + t * p.dx).toDouble(), (p.y1 + t * p.dy).toDouble())
    }

    private fun quadSane(q: Array<Point>, w: Int, h: Int): Boolean {
        if (q.any { it.x.isNaN() || it.y.isNaN() }) return false
        if (q.any { it.x < -0.25 * w || it.x > 1.25 * w || it.y < -0.25 * h || it.y > 1.25 * h }) return false
        val area = abs(shoelace(q))
        if (area < 0.012 * w * h) return false
        // Convexity: all cross products same sign.
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun shoelace(q: Array<Point>): Double {
        var a = 0.0
        for (i in 0 until 4) { val p = q[i]; val n = q[(i + 1) % 4]; a += p.x * n.y - n.x * p.y }
        return a / 2.0
    }

    /** Fraction of the quad perimeter actually backed by detected pixels, not inferred by intersection. */
    private fun edgeSupport(q: Array<Point>, a: Seg, b: Seg, top: Seg, bot: Seg): Float {
        fun cov(p0: Point, p1: Point, s: Seg): Float {
            val side = hypot(p1.x - p0.x, p1.y - p0.y).toFloat().takeIf { it > 1e-3f } ?: return 0f
            return (s.len / side).coerceAtMost(1f)
        }
        return 0.25f * (cov(q[0], q[3], a) + cov(q[1], q[2], b) + cov(q[0], q[1], top) + cov(q[3], q[2], bot))
    }

    /** Mean-intensity step across the boundary and interior texture, both normalised to 0..1. */
    private fun interiorStats(img: Mat, q: Array<Point>): Pair<Float, Float> {
        val x0 = q.minOf { it.x }; val x1 = q.maxOf { it.x }
        val y0 = q.minOf { it.y }; val y1 = q.maxOf { it.y }
        val iw = x1 - x0; val ih = y1 - y0
        val inner = clampRect(Rect((x0 + 0.22 * iw).toInt(), (y0 + 0.22 * ih).toInt(),
            (0.56 * iw).toInt(), (0.56 * ih).toInt()), img)
        val outer = clampRect(Rect((x0 - 0.16 * iw).toInt(), (y0 - 0.10 * ih).toInt(),
            (1.32 * iw).toInt(), (1.20 * ih).toInt()), img)
        if (inner == null || outer == null) return 0f to 0f
        val mIn = MatOfDouble(); val sIn = MatOfDouble()
        val roiIn = img.submat(inner); Core.meanStdDev(roiIn, mIn, sIn)
        val roiOut = img.submat(outer)
        // Surround mean with the interior's contribution removed, so the step is not self-diluted.
        val nOut = outer.width.toDouble() * outer.height
        val nIn = inner.width.toDouble() * inner.height
        val sumOut = Core.sumElems(roiOut).`val`[0]
        val meanIn = mIn.toArray()[0]
        val ring = ((sumOut - meanIn * nIn) / max(1.0, nOut - nIn))
        val delta = ((meanIn - ring) / 255.0).toFloat()
        val sd = (sIn.toArray()[0] / 128.0).toFloat().coerceIn(0f, 1f)
        listOf(mIn, sIn).forEach { it.release() }; roiIn.release(); roiOut.release()
        return delta to sd
    }

    private fun clampRect(r: Rect, img: Mat): Rect? {
        val x = r.x.coerceIn(0, img.width() - 1); val y = r.y.coerceIn(0, img.height() - 1)
        val w = r.width.coerceAtMost(img.width() - x); val h = r.height.coerceAtMost(img.height() - y)
        return if (w > 3 && h > 3) Rect(x, y, w, h) else null
    }

    /** height/width using averaged opposite sides, which cancels first-order perspective shear. */
    private fun aspectOf(q: Array<Point>): Float {
        val left = hypot(q[3].x - q[0].x, q[3].y - q[0].y)
        val right = hypot(q[2].x - q[1].x, q[2].y - q[1].y)
        val top = hypot(q[1].x - q[0].x, q[1].y - q[0].y)
        val bot = hypot(q[2].x - q[3].x, q[2].y - q[3].y)
        val wAvg = ((top + bot) / 2.0).takeIf { it > 1e-3 } ?: return 0f
        return (((left + right) / 2.0) / wAvg).toFloat()
    }

    private fun classify(aspect: Float, delta: Float, innerSd: Float, belowHorizon: Boolean): OpeningKind = when {
        aspect >= SizePriors.DOOR.minAspect && aspect <= SizePriors.DOOR.maxAspect && belowHorizon ->
            // A void reads as a large signed step with flat interior; a leaf keeps its own texture.
            if (abs(delta) > 0.16f && innerSd < 0.30f) OpeningKind.DOORWAY else OpeningKind.DOOR
        aspect in SizePriors.WINDOW.minAspect..SizePriors.WINDOW.maxAspect && abs(delta) > 0.10f ->
            OpeningKind.WINDOW
        else -> OpeningKind.UNKNOWN
    }

    private fun clampDist(v: Float, lo: Float, hi: Float) = when {
        v < lo -> lo - v
        v > hi -> v - hi
        else -> 0f
    }

    private fun nms(list: List<OpeningCandidate>, iouThresh: Float): List<OpeningCandidate> {
        val sorted = list.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<OpeningCandidate>()
        while (sorted.isNotEmpty()) {
            val a = sorted.removeAt(0); keep += a
            sorted.removeAll { iou(a, it) > iouThresh }
        }
        return keep
    }

    private fun iou(a: OpeningCandidate, b: OpeningCandidate): Float {
        fun bb(c: OpeningCandidate) = doubleArrayOf(
            c.corners.minOf { it.x }, c.corners.minOf { it.y },
            c.corners.maxOf { it.x }, c.corners.maxOf { it.y })
        val p = bb(a); val q = bb(b)
        val iw = min(p[2], q[2]) - max(p[0], q[0]); val ih = min(p[3], q[3]) - max(p[1], q[1])
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        val union = (p[2] - p[0]) * (p[3] - p[1]) + (q[2] - q[0]) * (q[3] - q[1]) - inter
        return (inter / max(1e-6, union)).toFloat()
    }
}
