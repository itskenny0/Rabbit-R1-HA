package com.github.itskenny0.r1ha.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.prefs.ThemeId

@Stable
data class SystemBarColors(val status: Color, val nav: Color, val lightIcons: Boolean = false)

/** What an EntityCard needs to render. Kept minimal and theme-agnostic. */
@Stable
data class CardRenderModel(
    val entityIdText: String,
    val friendlyName: String,
    val area: String?,
    val percent: Int,
    val isOn: Boolean,
    val domainGlyph: Glyph,
    val accent: AccentRole,
    val isAvailable: Boolean,
) {
    enum class Glyph { LIGHT, FAN, COVER, MEDIA_PLAYER }
    enum class AccentRole { WARM, COOL, GREEN, NEUTRAL }
}

interface R1Theme {
    val id: ThemeId
    val displayName: String
    val systemBars: SystemBarColors
    val baseline: ColorScheme

    @Composable fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit)
}

/** Shared baseline used by all three themes for non-card screens (settings, picker, about, onboarding). */
internal val sharedDarkBaseline: ColorScheme = darkColorScheme(
    primary = Color(0xFFF36F21),
    onPrimary = Color(0xFF1A0E04),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEDEDED),
)

/**
 * Spring-animated 0..1 fraction for the slider. Tuned for SNAPPY response — high stiffness with
 * almost no bounce — because the previous setting (medium stiffness, low bounce) made the bar
 * visibly lag behind the wheel by hundreds of ms when the user was spinning quickly. A tiny bit
 * of bounce is preserved so the bar still has a mechanical "ping" feel on big jumps.
 */
@Composable
internal fun rememberSliderFraction(percent: Int): Float {
    val target = percent.coerceIn(0, 100) / 100f
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = target,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.85f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh,
            visibilityThreshold = 0.001f,
        ),
        label = "sliderFraction",
    )
    return animated
}
