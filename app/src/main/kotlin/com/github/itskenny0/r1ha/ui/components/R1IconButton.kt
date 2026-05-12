package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * R1-styled equivalent of Material's `IconButton`. Same 44dp tap target so finger-size
 * doesn't change between this and the standard, but no ripple — the press feedback comes
 * from [r1Pressable] (scale + alpha) and a CLOCK_TICK haptic. Use anywhere an `IconButton`
 * would otherwise be a generic Material chip on top of the Mission Control language.
 */
@Composable
fun R1IconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = R1.Ink.copy(alpha = 0.85f),
    size: Dp = 44.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .then(if (enabled) Modifier.r1Pressable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else R1.Hairline,
            modifier = Modifier.size(iconSize),
        )
    }
}
