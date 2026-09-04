package com.minesafear.ar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.max
import kotlin.math.min

/**
 * Draws each detected obstacle as a traced outline over the real object, with
 * diagonal hatching inside it.
 *
 * The visual language is deliberately the one used for hazard marking on real
 * mine sites: a heavy warning-orange boundary, plus hatch strokes that make the
 * marked region read as *deliberately marked* rather than as a lighting artefact.
 * That distinction is the whole point — a translucent tint over a dark suitcase on
 * a pale floor is nearly invisible, whereas hatching survives any background.
 *
 * ## Hatching without a mesh
 *
 * The hatch is a set of parallel full-viewport lines, clipped to the contour path.
 * `clipPath` does the containment, so no per-object geometry is needed and the
 * hatch automatically follows the silhouette as the trace changes shape frame to
 * frame. Lines are generated along the bounding box diagonal extent so the pattern
 * covers the shape at any aspect ratio.
 */
@Composable
fun ObstacleSilhouetteLayer(
    contours: List<ObstacleContour>,
    modifier: Modifier = Modifier,
) {
    if (contours.isEmpty()) return

    // Slow travel on the hatch: motion is what makes an overlay read as UI rather
    // than as something physically on the floor. One full period per 1.4 s.
    val transition = rememberInfiniteTransition(label = "hazardHatch")
    val hatchPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hatchPhase",
    )

    Canvas(modifier = modifier) {
        contours.forEach { contour ->
            val severity = severityOf(contour)
            val path = contour.toPath(size.width, size.height) ?: return@forEach
            val bounds = DepthObstacleMask.boundsOf(contour)

            // Hatch first, clipped inside the shape; the outline goes over it so
            // the boundary stays crisp where hatch strokes meet it.
            clipPath(path) {
                drawHatch(
                    boundsNorm = bounds,
                    color = severity.hatch,
                    phase = hatchPhase,
                    density = density,
                )
            }

            // Soft outer glow: a second wider, translucent pass under the main
            // stroke, so the outline stays visible against both the pale floor and
            // dark objects without needing a drop shadow.
            drawPath(
                path = path,
                color = severity.glow,
                style = Stroke(
                    width = 11f * density,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round,
                ),
            )
            drawPath(
                path = path,
                color = severity.stroke,
                style = Stroke(
                    width = 4.5f * density,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round,
                ),
            )

            // Corner ticks at the bounding box: a reticle read, which tells the
            // trainee this is a system detection and not decoration.
            drawReticle(
                boundsNorm = bounds,
                color = severity.stroke,
                density = density,
            )
        }
    }
}

private class Severity(
    val stroke: Color,
    val glow: Color,
    val hatch: Color,
)

/**
 * Height above the floor drives colour. Distance is deliberately not used: a
 * knee-high slab is a hazard whether it is one metre away or four, and colour that
 * changes as the trainee walks reads as a bug.
 */
private fun severityOf(contour: ObstacleContour): Severity = when {
    contour.peakHeightMetres >= 0.45f -> Severity(
        stroke = Color(0xFFFF3D3D),
        glow = Color(0x55FF3D3D),
        hatch = Color(0x66FF3D3D),
    )
    contour.peakHeightMetres >= 0.15f -> Severity(
        stroke = Color(0xFFFF8A16), // warning orange, as in the reference
        glow = Color(0x55FF8A16),
        hatch = Color(0x66FFC66B),
    )
    else -> Severity(
        stroke = Color(0xFFFFD028),
        glow = Color(0x55FFD028),
        hatch = Color(0x55FFE485),
    )
}

/** View-normalized contour to a closed pixel-space [Path]. */
private fun ObstacleContour.toPath(widthPx: Float, heightPx: Float): Path? {
    val p = points
    if (p.size < 6) return null
    return Path().apply {
        moveTo(p[0] * widthPx, p[1] * heightPx)
        for (i in 2 until p.size step 2) {
            lineTo(p[i] * widthPx, p[i + 1] * heightPx)
        }
        close()
    }
}

/**
 * Parallel 45-degree lines covering [boundsNorm], offset by [phase] of one
 * spacing so the pattern drifts. Caller is responsible for clipping.
 *
 * Lines are drawn as y = x + c for c stepping across the full diagonal range, so
 * coverage holds regardless of how elongated the shape is.
 */
private fun DrawScope.drawHatch(
    boundsNorm: FloatArray,
    color: Color,
    phase: Float,
    density: Float,
) {
    val x0 = boundsNorm[0] * size.width
    val y0 = boundsNorm[1] * size.height
    val x1 = boundsNorm[2] * size.width
    val y1 = boundsNorm[3] * size.height

    val spacing = 22f * density
    // For y = x + c, the intercept c = y - x. Sweeping c from (y0 - x1) to
    // (y1 - x0) covers every diagonal that can intersect the box.
    val cMin = y0 - x1
    val cMax = y1 - x0
    val span = max(x1 - x0, y1 - y0) + spacing * 2f

    var c = cMin - spacing + phase * spacing
    while (c <= cMax + spacing) {
        // Extend each line well past the box; clipPath trims the overhang.
        drawLine(
            color = color,
            start = Offset(x0 - span, x0 - span + c),
            end = Offset(x1 + span, x1 + span + c),
            strokeWidth = 5f * density,
            cap = StrokeCap.Butt,
            blendMode = BlendMode.SrcOver,
        )
        c += spacing
    }
}

/** Four L-shaped corner marks around the obstacle's bounding box. */
private fun DrawScope.drawReticle(
    boundsNorm: FloatArray,
    color: Color,
    density: Float,
) {
    val pad = 6f * density
    val x0 = boundsNorm[0] * size.width - pad
    val y0 = boundsNorm[1] * size.height - pad
    val x1 = boundsNorm[2] * size.width + pad
    val y1 = boundsNorm[3] * size.height + pad

    // Tick length scales with the box but is clamped, so a small obstacle does not
    // get ticks that meet in the middle and a large one does not get hairlines.
    val armLen = min(min(x1 - x0, y1 - y0) * 0.22f, 34f * density)
    if (armLen <= 2f) return
    val w = 3f * density

    fun corner(cx: Float, cy: Float, sx: Float, sy: Float) {
        drawLine(color, Offset(cx, cy), Offset(cx + armLen * sx, cy), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(cx, cy), Offset(cx, cy + armLen * sy), strokeWidth = w, cap = StrokeCap.Round)
    }
    corner(x0, y0, 1f, 1f)
    corner(x1, y0, -1f, 1f)
    corner(x0, y1, 1f, -1f)
    corner(x1, y1, -1f, -1f)
}
