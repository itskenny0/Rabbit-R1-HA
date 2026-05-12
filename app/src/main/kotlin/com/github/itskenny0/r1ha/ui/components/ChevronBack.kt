package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Back affordance for screen top bars — 44dp tap target with a sharp two-stroke chevron
 * drawn by [Chevron] instead of Material's `ArrowBackIos`. Press feedback + haptic come
 * from [r1Pressable] so the whole bar feels uniformly tactile, no Material ripple anywhere.
 */
@Composable
fun ChevronBack(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .r1Pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(
            direction = ChevronDirection.Left,
            size = 12.dp,
            tint = R1.InkSoft,
        )
    }
}
