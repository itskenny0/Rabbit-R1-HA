package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.github.itskenny0.r1ha.core.theme.LocalR1Theme

@Composable
fun EntityCard(state: EntityState, onTapToggle: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val theme = LocalR1Theme.current
    val glyph = when (state.id.domain) {
        Domain.LIGHT -> CardRenderModel.Glyph.LIGHT
        Domain.FAN -> CardRenderModel.Glyph.FAN
        Domain.COVER -> CardRenderModel.Glyph.COVER
        Domain.MEDIA_PLAYER -> CardRenderModel.Glyph.MEDIA_PLAYER
    }
    val accent = when (state.id.domain) {
        Domain.LIGHT -> CardRenderModel.AccentRole.WARM
        Domain.FAN -> CardRenderModel.AccentRole.GREEN
        Domain.COVER -> CardRenderModel.AccentRole.NEUTRAL
        Domain.MEDIA_PLAYER -> CardRenderModel.AccentRole.COOL
    }
    theme.Card(
        model = CardRenderModel(
            entityIdText = state.id.value,
            friendlyName = state.friendlyName,
            area = state.area,
            percent = state.percent ?: 0,
            isOn = state.isOn,
            domainGlyph = glyph,
            accent = accent,
            isAvailable = state.isAvailable,
        ),
        modifier = modifier,
        onTapToggle = onTapToggle,
    )
}
