package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canvas-drawn speaker glyph used by the media-player card's mute button. Built
 * from primitives (lines + a small trapezoidal cone) rather than a Unicode emoji
 * so it renders identically across the R1's font situations and stays in the
 * sharp monochrome dashboard idiom instead of dropping a coloured emoji in.
 *
 * When [isMuted] is true the speaker is drawn with a diagonal slash from upper-
 * left to lower-right (the universal "no sound" convention) and the sound-wave
 * arcs on the right are suppressed. When false, two short arcs sit beside the
 * cone to indicate emission. The whole icon is stroked in [tint] at [strokeDp]
 * so it lives alongside the other 1.5 dp R1 hairlines.
 */
@Composable
fun SpeakerGlyph(
    isMuted: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeDp: Dp = 1.6.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val sw = strokeDp.toPx()
        val w = this.size.width
        val h = this.size.height

        // Speaker body — a small square on the left + a trapezoidal cone to its
        // right. Coordinates expressed as fractions of the canvas so the glyph
        // scales cleanly between e.g. the 20 dp control-row size and a smaller
        // 14 dp use elsewhere if we ever inline it.
        val bodyLeft = w * 0.10f
        val bodyRight = w * 0.32f
        val bodyTop = h * 0.40f
        val bodyBottom = h * 0.60f

        val coneTop = h * 0.20f
        val coneBottom = h * 0.80f
        val coneRight = w * 0.55f

        val path = Path().apply {
            moveTo(bodyLeft, bodyTop)
            lineTo(bodyRight, bodyTop)
            lineTo(coneRight, coneTop)
            lineTo(coneRight, coneBottom)
            lineTo(bodyRight, bodyBottom)
            lineTo(bodyLeft, bodyBottom)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = sw, cap = StrokeCap.Round))

        if (isMuted) {
            // Diagonal slash through the speaker — universally read as "muted".
            drawLine(
                color = tint,
                start = Offset(w * 0.08f, h * 0.18f),
                end = Offset(w * 0.92f, h * 0.82f),
                strokeWidth = sw * 1.1f,
                cap = StrokeCap.Round,
            )
        } else {
            // Two emission arcs to the right of the cone — drawn as short lines
            // at the wave-tip locations for crispness on the R1's small display
            // (a real arc curve at this size looks like a smudge). Close arc =
            // mid-volume wave, far arc = peak wave, both nudged outward from
            // the cone tip.
            val arc1X = w * 0.66f
            val arc2X = w * 0.80f
            drawLine(
                color = tint,
                start = Offset(arc1X, h * 0.32f),
                end = Offset(arc1X, h * 0.68f),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = Offset(arc2X, h * 0.22f),
                end = Offset(arc2X, h * 0.78f),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}
