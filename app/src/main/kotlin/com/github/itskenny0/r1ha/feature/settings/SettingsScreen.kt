package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository

/** Placeholder — replaced in M14. */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onOpenThemePicker: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text("Settings — coming soon", modifier = Modifier.wrapContentSize())
    }
}
