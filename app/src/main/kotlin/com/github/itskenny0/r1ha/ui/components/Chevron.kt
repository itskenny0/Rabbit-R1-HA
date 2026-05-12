package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Drawing primitive — a sharp two-stroke chevron in the R1 idiom. Replaces Material's
 * `ArrowForwardIos` / `ArrowBackIos` / `ArrowUp` / `ArrowDown`, all of which have a slight
 * rounded geometry and Material-typical proportions that fight the dashboard language.
 *
 * Built from two `drawLine` calls (with `Butt` caps so the joint reads as a hard angle) so it
 * matches the 1dp hairlines used elsewhere. Stroke width scales lightly with the requested
 * size so a 12dp chevron stays crisp without being feather-thin.
 */
enum class ChevronDirection { Left, Right, Up, Down }

@Composable
fun Chevron(
    direction: ChevronDirection,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    tint: Color = R1.InkMuted,
    strokeWidth: Dp = 1.5.dp,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val sw = strokeWidth.toPx()
            val w = this.size.width
            val h = this.size.height
            // Inset slightly so the stroke doesn't clip at the box edges.
            val inset = sw / 2f
            // Anchor + the two end points; orientation flips which corners we connect.
            val (a, mid, b) = when (direction) {
                ChevronDirection.Right -> Triple(
                    Offset(inset, inset),
                    Offset(w - inset, h / 2f),
                    Offset(inset, h - inset),
                )
                ChevronDirection.Left -> Triple(
                    Offset(w - inset, inset),
                    Offset(inset, h / 2f),
                    Offset(w - inset, h - inset),
                )
                ChevronDirection.Down -> Triple(
                    Offset(inset, inset),
                    Offset(w / 2f, h - inset),
                    Offset(w - inset, inset),
                )
                ChevronDirection.Up -> Triple(
                    Offset(inset, h - inset),
                    Offset(w / 2f, inset),
                    Offset(w - inset, h - inset),
                )
            }
            drawLine(tint, start = a, end = mid, strokeWidth = sw, cap = StrokeCap.Butt)
            drawLine(tint, start = mid, end = b, strokeWidth = sw, cap = StrokeCap.Butt)
        }
    }
}
