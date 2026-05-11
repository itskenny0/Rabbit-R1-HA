package com.github.itskenny0.r1ha.feature.themepicker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository

/** Placeholder — replaced in M14. */
@Composable
fun ThemePickerScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text("ThemePicker — coming soon", modifier = Modifier.wrapContentSize())
    }
}
