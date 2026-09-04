package com.minesafear.ar.openings

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

private val SAFETY_GREEN = Color(0xFF00843D)   // ISO 7010 E001 safety green
private val AMBER = Color(0xFFF2A100)
private val SIGN_W_M = 0.46f
private val SIGN_H_M = 0.17f
private val LINTEL_GAP_M = 0.14f

/**
 * Project a shape defined in the opening's own plane (metres, origin at opening centre,
 * X right / Y up) into analysis-image pixels. Every vertex goes through the full 3D pose, so the
 * board keeps correct perspective foreshortening instead of being billboarded flat at the camera.
 */
internal fun OpeningTrack.projectLocal(
    pts: List<FloatArray>, intr: Intrinsics, rWorldFromCam: FloatArray
): List<Offset>? {
    val rCamFromWorld = M3.transpose(rWorldFromCam)
    val tCam = M3.mulVec(rCamFromWorld,
        floatArrayOf(dirWorld[0] * distanceM, dirWorld[1] * distanceM, dirWorld[2] * distanceM))
    if (tCam[2] <= 0.3f) return null
    val rCamFromOpening = M3.mul(rCamFromWorld, rWorldFromOpening)
    val out = ArrayList<Offset>(pts.size)
    for (p in pts) {
        val v = M3.mulVec(rCamFromOpening, p)
        val q = floatArrayOf(v[0] + tCam[0], v[1] + tCam[1], v[2] + tCam[2])
        val px = intr.project(q) ?: return null
        out += Offset(px[0], px[1])
    }
    return out
}

/**
 * Analysis-image pixels -> view pixels for a PreviewView in FILL_CENTER (the default).
 * If the preview scale type is changed to FIT_CENTER this must switch to min(), or the overlay
 * will sit a few percent off the real door edge — the classic AR-overlay misalignment bug.
 */
private class ViewMap(imgW: Int, imgH: Int, viewW: Float, viewH: Float) {
    private val s = max(viewW / imgW, viewH / imgH)
    private val dx = (viewW - imgW * s) / 2f
    private val dy = (viewH - imgH * s) / 2f
    fun map(o: Offset) = Offset(o.x * s + dx, o.y * s + dy)
    val scale get() = s
}

/**
 * Draws detected egress features over the camera preview. Doors and doorways get a green EXIT board
 * above the lintel with a directional arrow; windows get an amber secondary-egress marker, because
 * calling a window an exit without qualification is a safety-signage error.
 */
@Composable
fun OpeningOverlay(
    frame: OpeningFrame?,
    rWorldFromCam: FloatArray?,
    modifier: Modifier = Modifier,
    showDebugQuads: Boolean = false
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        if (frame == null || rWorldFromCam == null) return@Canvas
        val vm = ViewMap(frame.imageWidth, frame.imageHeight, size.width, size.height)
        for (t in frame.tracks) {
            val quad = t.project(frame.intrinsics, rWorldFromCam)?.map { vm.map(Offset(it.x.toFloat(), it.y.toFloat())) }
                ?: continue
            if (showDebugQuads) drawQuad(quad, Color.Cyan.copy(alpha = 0.7f), dashed = true)
            when (t.kind) {
                OpeningKind.DOOR, OpeningKind.DOORWAY -> drawExitSign(t, frame, rWorldFromCam, vm, measurer, quad)
                OpeningKind.WINDOW -> drawWindowMarker(t, frame, rWorldFromCam, vm, measurer, quad)
                OpeningKind.UNKNOWN -> Unit
            }
        }
    }
}

private fun DrawScope.drawExitSign(
    t: OpeningTrack, frame: OpeningFrame, rWFC: FloatArray,
    vm: ViewMap, measurer: TextMeasurer, doorQuad: List<Offset>
) {
    val hh = t.heightM / 2f
    val yTop = hh + LINTEL_GAP_M + SIGN_H_M
    val yBot = hh + LINTEL_GAP_M
    val hw = SIGN_W_M / 2f
    val board = t.projectLocal(listOf(
        floatArrayOf(-hw, yTop, 0.02f), floatArrayOf(hw, yTop, 0.02f),
        floatArrayOf(hw, yBot, 0.02f), floatArrayOf(-hw, yBot, 0.02f)
    ), frame.intrinsics, rWFC)?.map { vm.map(it) } ?: return

    // Door jambs, so the operator can see what the sign is attached to.
    drawQuad(doorQuad, SAFETY_GREEN.copy(alpha = 0.85f), strokeWidth = 3f * vm.scale.coerceIn(0.5f, 2f))
    drawPath(pathOf(doorQuad), SAFETY_GREEN.copy(alpha = 0.10f))

    drawPath(pathOf(board), SAFETY_GREEN.copy(alpha = 0.92f))
    drawQuad(board, Color.White, strokeWidth = 2f)

    // Arrow drawn in board-local metres, projected vertex by vertex to keep perspective honest.
    val ay = (yTop + yBot) / 2f
    val ah = SIGN_H_M * 0.30f
    val arrow = t.projectLocal(listOf(
        floatArrayOf(0.02f, ay + ah, 0.021f), floatArrayOf(hw * 0.80f, ay, 0.021f),
        floatArrayOf(0.02f, ay - ah, 0.021f), floatArrayOf(0.02f, ay - ah * 0.42f, 0.021f),
        floatArrayOf(-hw * 0.34f, ay - ah * 0.42f, 0.021f), floatArrayOf(-hw * 0.34f, ay + ah * 0.42f, 0.021f),
        floatArrayOf(0.02f, ay + ah * 0.42f, 0.021f)
    ), frame.intrinsics, rWFC)?.map { vm.map(it) }
    if (arrow != null) drawPath(pathOf(arrow), Color.White)

    val label = if (t.kind == OpeningKind.DOORWAY) "EXIT" else "EXIT DOOR"
    drawSignText(board, "$label  ·  निकास", measurer, Color.White, widthFrac = 0.52f, xOffsetFrac = -0.20f)
    drawRangeTag(doorQuad, t, measurer)
}

private fun DrawScope.drawWindowMarker(
    t: OpeningTrack, frame: OpeningFrame, rWFC: FloatArray,
    vm: ViewMap, measurer: TextMeasurer, quad: List<Offset>
) {
    drawQuad(quad, AMBER.copy(alpha = 0.9f), strokeWidth = 3f, dashed = true)
    drawPath(pathOf(quad), AMBER.copy(alpha = 0.10f))
    val hw = (t.widthM * 0.42f) / 2f
    val hb = (t.heightM * 0.20f) / 2f
    val board = t.projectLocal(listOf(
        floatArrayOf(-hw, hb, 0.02f), floatArrayOf(hw, hb, 0.02f),
        floatArrayOf(hw, -hb, 0.02f), floatArrayOf(-hw, -hb, 0.02f)
    ), frame.intrinsics, rWFC)?.map { vm.map(it) } ?: return
    drawPath(pathOf(board), AMBER.copy(alpha = 0.92f))
    drawSignText(board, "SECONDARY EGRESS", measurer, Color.Black, widthFrac = 0.88f, xOffsetFrac = 0f)
    drawRangeTag(quad, t, measurer)
}

/** Text cannot be perspective-warped in Compose, so it is centred, rotated to the board's top edge
 *  and uniformly scaled to fit — visually indistinguishable at signage sizes. */
private fun DrawScope.drawSignText(
    board: List<Offset>, text: String, measurer: TextMeasurer,
    color: Color, widthFrac: Float, xOffsetFrac: Float
) {
    val top = board[1] - board[0]
    val boardW = hypot(top.x, top.y)
    if (boardW < 26f) return                       // too small to read; the arrow alone carries it
    val angle = Math.toDegrees(atan2(top.y, top.x).toDouble()).toFloat()
    val centre = Offset(board.sumOf { it.x.toDouble() }.toFloat() / 4f,
        board.sumOf { it.y.toDouble() }.toFloat() / 4f)
    val style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 0.5.sp)
    val layout = measurer.measure(text, style)
    val target = boardW * widthFrac
    val k = target / layout.size.width.coerceAtLeast(1)
    withTransform({
        rotate(angle, centre)
        scale(k, k, centre)
        translate(boardW * xOffsetFrac / k, 0f)
    }) {
        drawText(layout, color = color,
            topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f))
    }
}

/** Range readout. A tilde marks a distance derived from a size assumption rather than the floor. */
private fun DrawScope.drawRangeTag(quad: List<Offset>, t: OpeningTrack, measurer: TextMeasurer) {
    val prefix = if (t.scaleSource == ScaleSource.SIZE_PRIOR) "~" else ""
    val txt = "$prefix${"%.1f".format(t.distanceM)} m"
    val layout = measurer.measure(txt, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    val anchor = quad[3]
    drawText(layout, color = Color.White, topLeft = Offset(anchor.x + 6f, anchor.y - layout.size.height - 4f))
}

private fun pathOf(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    close()
}

private fun DrawScope.drawQuad(pts: List<Offset>, color: Color, strokeWidth: Float = 3f, dashed: Boolean = false) {
    drawPath(pathOf(pts), color, style = Stroke(
        width = strokeWidth,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null
    ))
}
