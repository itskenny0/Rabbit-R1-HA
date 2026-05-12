package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Two-position toggle in the R1 idiom. Sharp 4dp slot rather than a Material pill, hairline
 * border when OFF so the slot reads against the near-black background, full accent fill when
 * ON. Thumb is a square (1dp soften), positioned by `Modifier.offset` so a spring overshoot
 * can't crash padding (same lesson as the switch card).
 *
 * Intentionally smaller than Material's switch (32×14dp track vs ~52×32dp) — R1's 240px-wide
 * display can't spare the room, and the visual mass already pulls focus away from the row
 * label, which is what the user is actually reading.
 */
@Composable
fun R1Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = R1.AccentWarm,
    enabled: Boolean = true,
) {
    val trackWidth = 32.dp
    val trackHeight = 14.dp
    val thumbSize = 10.dp
    val sidePadding = 2.dp
    val travel = trackWidth - thumbSize - sidePadding * 2

    val rawFrac by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "r1-switch-frac",
    )
    val frac = rawFrac.coerceIn(0f, 1f)

    val trackBg by animateColorAsState(
        targetValue = if (!enabled) R1.SurfaceMuted else if (checked) accent else R1.Bg,
        label = "r1-switch-track",
    )
    val borderColor = if (!enabled) R1.Hairline else if (checked) accent else R1.Hairline
    val thumbColor = if (!enabled) R1.InkMuted else if (checked) R1.Bg else R1.InkSoft

    Box(
        modifier = modifier
            .then(if (enabled) Modifier.r1Pressable(onClick = { onCheckedChange(!checked) }) else Modifier)
            .width(trackWidth)
            .height(trackHeight)
            .clip(R1.ShapeM)
            .background(trackBg)
            .border(1.dp, borderColor, R1.ShapeM),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = sidePadding + travel * frac)
                .width(thumbSize)
                .height(thumbSize)
                .clip(RoundedCornerShape(1.dp))
                .background(thumbColor),
        )
    }
}
