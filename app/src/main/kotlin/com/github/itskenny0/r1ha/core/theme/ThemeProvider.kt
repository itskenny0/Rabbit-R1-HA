package com.github.itskenny0.r1ha.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.github.itskenny0.r1ha.core.prefs.ThemeId

val LocalR1Theme = staticCompositionLocalOf<R1Theme> { PragmaticHybridTheme }

@Composable
fun R1ThemeHost(themeId: ThemeId, content: @Composable () -> Unit) {
    val theme = when (themeId) {
        ThemeId.MINIMAL_DARK -> MinimalDarkTheme
        ThemeId.PRAGMATIC_HYBRID -> PragmaticHybridTheme
        ThemeId.COLORFUL_CARDS -> ColorfulCardsTheme
    }
    CompositionLocalProvider(LocalR1Theme provides theme) {
        MaterialTheme(colorScheme = theme.baseline, content = content)
    }
}
