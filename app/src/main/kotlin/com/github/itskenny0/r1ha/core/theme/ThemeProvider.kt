package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.prefs.UiOptions

val LocalR1Theme = staticCompositionLocalOf<R1Theme> { PragmaticHybridTheme }

/** UI options surfaced to themes so they can honour user toggles without taking extra params. */
val LocalUiOptions = staticCompositionLocalOf { UiOptions() }

/**
 * Repository handle injected near the top of each screen that needs it (CardStackScreen,
 * FavoritesPickerScreen) so deep composables — [com.github.itskenny0.r1ha.ui.components.SensorCard]
 * especially — can fetch history without every wrapper threading the repository through
 * its parameter list. Null by default; consumers handle that gracefully (skip the chart,
 * skip the history list).
 */
val LocalHaRepository = staticCompositionLocalOf<com.github.itskenny0.r1ha.core.ha.HaRepository?> { null }

/**
 * Per-entity overrides surfaced to deep card composables so the rename / display /
 * long-press customizations can apply without each theme threading them through. The
 * EntityCard wrapper looks up the override for its entity_id and merges visibility
 * fields into a per-card [LocalUiOptions] before invoking the theme's Card. Empty map
 * by default (the wrapper handles the missing-key case gracefully).
 */
val LocalEntityOverrides = staticCompositionLocalOf<Map<String, com.github.itskenny0.r1ha.core.prefs.EntityOverride>> { emptyMap() }

/**
 * Callback for the BigReadout's tap-to-cycle gesture on light cards. Themes' BigReadout
 * composables consult this; null disables the gesture (used by previews / non-light
 * paths). Wired by CardStackScreen from CardStackViewModel.cycleLightWheelMode.
 */
val LocalOnCycleLightMode = staticCompositionLocalOf<((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?> { null }

/** Same idea for the light-effect cycle gesture: tap the effect chip → next effect. */
val LocalOnCycleLightEffect = staticCompositionLocalOf<((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?> { null }

@Composable
fun R1ThemeHost(themeId: ThemeId, content: @Composable () -> Unit) {
    val theme = when (themeId) {
        ThemeId.MINIMAL_DARK -> MinimalDarkTheme
        ThemeId.PRAGMATIC_HYBRID -> PragmaticHybridTheme
        ThemeId.COLORFUL_CARDS -> ColorfulCardsTheme
    }
    CompositionLocalProvider(LocalR1Theme provides theme) {
        MaterialTheme(colorScheme = theme.baseline) {
            // Wrap in a Surface so LocalContentColor is propagated to all descendants,
            // otherwise Text composables without explicit `color` fall back to Color.Black.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
