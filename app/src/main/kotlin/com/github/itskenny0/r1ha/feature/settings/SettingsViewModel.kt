package com.github.itskenny0.r1ha.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.Behavior
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.prefs.UiOptions
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.core.prefs.WheelSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    // ── Wheel ───────────────────────────────────────────────────────────────

    fun setWheelStep(step: Int) = update { it.copy(wheel = it.wheel.copy(stepPercent = step)) }
    fun setWheelAcceleration(enabled: Boolean) = update { it.copy(wheel = it.wheel.copy(acceleration = enabled)) }
    fun setWheelInvert(inverted: Boolean) = update { it.copy(wheel = it.wheel.copy(invertDirection = inverted)) }
    fun setWheelKeySource(source: WheelKeySource) = update { it.copy(wheel = it.wheel.copy(keySource = source)) }

    // ── Card UI ─────────────────────────────────────────────────────────────

    fun setDisplayMode(mode: DisplayMode) = update { it.copy(ui = it.ui.copy(displayMode = mode)) }
    fun setShowOnOffPill(show: Boolean) = update { it.copy(ui = it.ui.copy(showOnOffPill = show)) }
    fun setShowAreaLabel(show: Boolean) = update { it.copy(ui = it.ui.copy(showAreaLabel = show)) }
    fun setShowPositionDots(show: Boolean) = update { it.copy(ui = it.ui.copy(showPositionDots = show)) }

    // ── Behavior ────────────────────────────────────────────────────────────

    fun setHaptics(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(haptics = enabled)) }
    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(keepScreenOn = enabled)) }
    fun setTapToToggle(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(tapToToggle = enabled)) }

    // ── Appearance ──────────────────────────────────────────────────────────

    fun setTheme(themeId: ThemeId) = update { it.copy(theme = themeId) }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    companion object {
        fun factory(settings: SettingsRepository) = viewModelFactory {
            initializer { SettingsViewModel(settings = settings) }
        }
    }
}
