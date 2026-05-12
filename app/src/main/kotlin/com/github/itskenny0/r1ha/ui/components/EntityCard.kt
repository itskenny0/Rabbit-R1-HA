package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.github.itskenny0.r1ha.core.theme.LocalR1Theme

@Composable
fun EntityCard(
    state: EntityState,
    onTapToggle: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    onSetOn: ((Boolean) -> Unit)? = null,
    /**
     * When true the entire card surface is tappable; tapping calls [onTapToggle]. When
     * false the card is inert (the wheel and the explicit ON/OFF labels on switch cards
     * still work). Mirrors the "Tap to toggle" setting in Settings, which used to be
     * silently dead-code because the three theme implementations of `theme.Card` never
     * wired their `onTapToggle` parameter to a `Modifier.clickable` — fixed here once for
     * all themes by wrapping the theme card in our own pressable Box.
     */
    tapToToggleEnabled: Boolean = true,
) {
    val theme = LocalR1Theme.current
    val glyph = when (state.id.domain) {
        Domain.LIGHT -> CardRenderModel.Glyph.LIGHT
        Domain.FAN -> CardRenderModel.Glyph.FAN
        Domain.COVER -> CardRenderModel.Glyph.COVER
        Domain.MEDIA_PLAYER -> CardRenderModel.Glyph.MEDIA_PLAYER
    }
    val accentRole = when (state.id.domain) {
        Domain.LIGHT -> CardRenderModel.AccentRole.WARM
        Domain.FAN -> CardRenderModel.AccentRole.GREEN
        Domain.COVER -> CardRenderModel.AccentRole.NEUTRAL
        Domain.MEDIA_PLAYER -> CardRenderModel.AccentRole.COOL
    }
    // When the entity is unavailable, dim the whole card and overlay a "UNAVAILABLE" label so
    // the user doesn't think the card is just at 0%. The themes themselves don't honour
    // isAvailable, so this is enforced uniformly at the wrapper level. The tap-to-toggle
    // gesture is also wired here (rather than inside each theme) so all three themes get it
    // for free; r1Pressable's haptic is disabled because the existing percent-change effect
    // in CardStackScreen already fires CLOCK_TICK when the state actually flips — double-
    // haptic on a single tap reads as a stutter rather than a click.
    val tapModifier = if (tapToToggleEnabled && state.isAvailable) {
        Modifier.r1Pressable(onClick = onTapToggle, hapticOnClick = false)
    } else {
        Modifier
    }
    Box(modifier = modifier.then(tapModifier)) {
        val themeAlpha = if (state.isAvailable) 1f else 0.35f
        if (!state.supportsScalar) {
            // On/off-only entity — render the switch variant. We bypass the theme.Card path
            // because the percent slider doesn't make sense here; the switch card uses the
            // same R1 design tokens so it looks consistent across themes.
            SwitchCard(
                state = state,
                accent = resolveAccentColor(accentRole),
                domainLabel = domainLabel(glyph),
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                onTapToggle = onTapToggle,
                onSetOn = onSetOn ?: { _ -> onTapToggle() },
                modifier = Modifier.fillMaxSize().alpha(themeAlpha),
            )
        } else {
            theme.Card(
                model = CardRenderModel(
                    entityIdText = state.id.value,
                    friendlyName = state.friendlyName,
                    area = state.area,
                    percent = state.percent ?: 0,
                    isOn = state.isOn,
                    domainGlyph = glyph,
                    accent = accentRole,
                    isAvailable = state.isAvailable,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(themeAlpha),
                onTapToggle = onTapToggle,
            )
        }
        if (!state.isAvailable) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "UNAVAILABLE",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun resolveAccentColor(role: CardRenderModel.AccentRole) = when (role) {
    CardRenderModel.AccentRole.WARM -> com.github.itskenny0.r1ha.core.theme.R1.AccentWarm
    CardRenderModel.AccentRole.COOL -> com.github.itskenny0.r1ha.core.theme.R1.AccentCool
    CardRenderModel.AccentRole.GREEN -> com.github.itskenny0.r1ha.core.theme.R1.AccentGreen
    CardRenderModel.AccentRole.NEUTRAL -> com.github.itskenny0.r1ha.core.theme.R1.AccentNeutral
}

private fun domainLabel(glyph: CardRenderModel.Glyph): String = when (glyph) {
    CardRenderModel.Glyph.LIGHT -> "LIGHT"
    CardRenderModel.Glyph.FAN -> "FAN"
    CardRenderModel.Glyph.COVER -> "COVER"
    CardRenderModel.Glyph.MEDIA_PLAYER -> "MEDIA"
}
