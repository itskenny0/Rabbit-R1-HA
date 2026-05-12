package com.github.itskenny0.r1ha.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView

/**
 * Make any element tactile in the R1 idiom: a tight scale + alpha dip on press, and a
 * CLOCK_TICK haptic on click. No Material ripple — the ripple's watercolour splash fights
 * the sharp industrial dashboard language. The scale dip is animated with a critically-
 * damped spring so it feels mechanical (snap, no overshoot).
 *
 * Use everywhere a clickable row / chip / icon button would otherwise be a bare
 * [Modifier.clickable]: settings rows, nav rows, theme cards, info-row link, etc. The 0.97
 * scale and 0.78 pressed-alpha are deliberately subtle — visible enough that the press
 * registers, quiet enough that scrolling lists don't shimmer if a stray finger grazes them.
 */
fun Modifier.r1Pressable(
    onClick: () -> Unit,
    hapticOnClick: Boolean = true,
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.78f,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-press-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-press-alpha",
    )
    val view = LocalView.current
    this
        // Single graphicsLayer for both transforms — cheaper than chaining .graphicsLayer { scale }
        // and .alpha(), both of which would force separate compositing layers.
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                if (hapticOnClick) {
                    @Suppress("DEPRECATION")  // CLOCK_TICK is stable on our minSdk 33
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                onClick()
            },
        )
}
