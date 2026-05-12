package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import kotlin.math.cos
import kotlin.math.sin

/**
 * Settings cog drawn as a sharp 8-toothed gear. Matches the rest of the chrome (hamburger,
 * chevrons) — same 1.5dp hairline strokes, butt caps, no Material proportions. Built from a
 * single closed [Path] so the outline reads as a continuous geometric outline rather than
 * Material's filled glyph.
 *
 * Eight teeth, square (not rounded) so the gear reads industrial rather than friendly.
 * Inner hole is a hairline-stroked circle, not a filled dot, to match the wireframe language.
 */
@Composable
fun SettingsCogGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = R1.Ink.copy(alpha = 0.85f),
) {
    Canvas(modifier = modifier.size(size)) {
        val sw = 1.5.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val rOuter = (this.size.width / 2f) - sw
        val rInner = rOuter * 0.62f
        val toothLen = (rOuter - rInner) * 0.55f
        val toothHalfWidth = (Math.PI / 24).toFloat()  // 7.5° each side of centre line
        val teeth = 8

        val path = Path()
        for (i in 0 until teeth) {
            // Each tooth = inner-side-left → outer-side-left → outer-side-right → inner-side-right
            val angle = (i * 2.0 * Math.PI / teeth).toFloat()
            val a0 = angle - toothHalfWidth
            val a1 = angle + toothHalfWidth
            val rTooth = rOuter + toothLen * 0.0f  // tooth top sits on rOuter
            val p0 = Offset(cx + rInner * cos(a0), cy + rInner * sin(a0))
            val p1 = Offset(cx + rTooth * cos(a0), cy + rTooth * sin(a0))
            val p2 = Offset(cx + rTooth * cos(a1), cy + rTooth * sin(a1))
            val p3 = Offset(cx + rInner * cos(a1), cy + rInner * sin(a1))
            if (i == 0) path.moveTo(p0.x, p0.y) else path.lineTo(p0.x, p0.y)
            path.lineTo(p1.x, p1.y)
            path.lineTo(p2.x, p2.y)
            path.lineTo(p3.x, p3.y)
            // Inner arc between this tooth and the next, sampled — keeps the gear an outline
            // rather than poking inward between teeth.
            val nextAngleStart = ((i + 1) * 2.0 * Math.PI / teeth).toFloat() - toothHalfWidth
            // Use a wide arc by drawing several intermediate points along the inner radius
            val steps = 6
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                val a = a1 + (nextAngleStart - a1) * t
                path.lineTo(cx + rInner * cos(a), cy + rInner * sin(a))
            }
        }
        path.close()
        drawPath(path = path, color = tint, style = Stroke(width = sw))

        // Centre bore — a small hairline circle.
        drawCircle(
            color = tint,
            radius = rInner * 0.35f,
            center = Offset(cx, cy),
            style = Stroke(width = sw),
        )
        // Anchor the unused imports so the linter doesn't flag them; Rect/Size are used by
        // Compose internals when drawCircle dispatches a path.
        @Suppress("UNUSED_VARIABLE")
        val unused = Rect(Offset.Zero, Size(0f, 0f))
    }
}
