package com.github.itskenny0.r1ha.feature.themepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.R1Theme
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
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
    PragmaticHybridTheme,
    MinimalDarkTheme,
    ColorfulCardsTheme,
)

@Composable
fun ThemePickerScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val currentThemeId = appSettings.theme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "THEME", onBack = onBack)

        Column(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
            ALL_THEMES.forEach { theme ->
                ThemeRow(
                    theme = theme,
                    isSelected = theme.id == currentThemeId,
                    onClick = {
                        scope.launch {
                            settings.update { it.copy(theme = theme.id) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    theme: R1Theme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection indicator — accent fill when active, otherwise a hairline-outlined hollow
        // square so the unselected state is *visible* on the near-black background instead of
        // a hairline-coloured 14dp square that just disappeared into the bg.
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(R1.ShapeS)
                .background(if (isSelected) R1.AccentWarm else R1.Bg)
                .then(
                    if (isSelected) Modifier
                    else Modifier.border(1.dp, R1.InkMuted, R1.ShapeS),
                ),
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.displayName.uppercase(),
                style = R1.labelMicro,
                color = if (isSelected) R1.AccentWarm else R1.InkSoft,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = theme.id.name.lowercase().replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
                style = R1.body,
                color = R1.Ink,
            )
        }

        Spacer(Modifier.width(14.dp))

        // Miniature preview — render the actual theme.Card with a sample model.
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 112.dp)
                .clip(R1.ShapeS)
                .border(
                    width = if (isSelected) 1.dp else 1.dp,
                    color = if (isSelected) R1.AccentWarm else R1.Hairline,
                    shape = R1.ShapeS,
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
