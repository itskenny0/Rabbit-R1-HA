package com.github.itskenny0.r1ha.feature.themepicker

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.github.itskenny0.r1ha.core.theme.ColorfulCardsTheme
import com.github.itskenny0.r1ha.core.theme.MinimalDarkTheme
import com.github.itskenny0.r1ha.core.theme.PragmaticHybridTheme
import com.github.itskenny0.r1ha.core.theme.R1Theme
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.ui.components.ChevronBack
import kotlinx.coroutines.launch

private val SAMPLE_CARD = CardRenderModel(
    entityIdText = "light.living_room",
    friendlyName = "Living Room",
    area = "Lounge",
    percent = 72,
    isOn = true,
    domainGlyph = CardRenderModel.Glyph.LIGHT,
    accent = CardRenderModel.AccentRole.WARM,
    isAvailable = true,
)

private val ALL_THEMES: List<R1Theme> = listOf(
    MinimalDarkTheme,
    PragmaticHybridTheme,
    ColorfulCardsTheme,
)

@Composable
fun ThemePickerScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appSettings by settings.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(),
    )
    val currentTheme = appSettings.theme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            ChevronBack(onClick = onBack)
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(ALL_THEMES) { theme ->
                ThemeRow(
                    theme = theme,
                    isSelected = theme.id == currentTheme,
                    onClick = {
                        scope.launch {
                            settings.update { it.copy(theme = theme.id) }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ThemeRow(
    theme: R1Theme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = theme.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // ── Miniature live preview ──────────────────────────────────────────
        // Clip tightly so the spring-animated slider fill cannot overflow the box.
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 100.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(6.dp),
                ),
        ) {
            R1ThemeHost(themeId = theme.id) {
                theme.Card(
                    model = SAMPLE_CARD,
                    modifier = Modifier.fillMaxSize(),
                    onTapToggle = {},
                )
            }
        }
    }
}
