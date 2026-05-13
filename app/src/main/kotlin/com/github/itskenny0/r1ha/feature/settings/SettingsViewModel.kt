package com.github.itskenny0.r1ha.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
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
    fun setAccelerationCurve(curve: com.github.itskenny0.r1ha.core.prefs.AccelerationCurve) =
        update { it.copy(wheel = it.wheel.copy(accelerationCurve = curve)) }

    // ── Card UI ─────────────────────────────────────────────────────────────

    fun setDisplayMode(mode: DisplayMode) = update { it.copy(ui = it.ui.copy(displayMode = mode)) }
    fun setShowOnOffPill(show: Boolean) = update { it.copy(ui = it.ui.copy(showOnOffPill = show)) }
    fun setShowAreaLabel(show: Boolean) = update { it.copy(ui = it.ui.copy(showAreaLabel = show)) }
    fun setShowPositionDots(show: Boolean) = update { it.copy(ui = it.ui.copy(showPositionDots = show)) }
    fun setHideCardTailAbove(hide: Boolean) = update { it.copy(ui = it.ui.copy(hideCardTailAbove = hide)) }
    fun setMaxDecimalPlaces(n: Int) = update { it.copy(ui = it.ui.copy(maxDecimalPlaces = n)) }
    fun setTempUnit(u: com.github.itskenny0.r1ha.core.prefs.TemperatureUnit) =
        update { it.copy(ui = it.ui.copy(tempUnit = u)) }
    fun setInfiniteScroll(enabled: Boolean) = update { it.copy(ui = it.ui.copy(infiniteScroll = enabled)) }

    // ── Behavior ────────────────────────────────────────────────────────────

    fun setHaptics(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(haptics = enabled)) }
    fun setKeepScreenOn(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(keepScreenOn = enabled)) }
    fun setTapToToggle(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(tapToToggle = enabled)) }
    fun setHideStatusBar(enabled: Boolean) = update { it.copy(behavior = it.behavior.copy(hideStatusBar = enabled)) }
    fun setWheelTogglesSwitches(enabled: Boolean) =
        update { it.copy(behavior = it.behavior.copy(wheelTogglesSwitches = enabled)) }

    // ── Appearance ──────────────────────────────────────────────────────────

    fun setTheme(themeId: ThemeId) = update { it.copy(theme = themeId) }

    // ── Account ─────────────────────────────────────────────────────────────

    /**
     * Sign out: clears tokens and the server URL so the next launch routes back to onboarding.
     * Reports completion via toast so the user knows it landed.
     */
    fun signOut(onAfter: () -> Unit) {
        viewModelScope.launch {
            R1Log.i("Settings.signOut", "starting")
            tokens.clear()
            settings.update { it.copy(server = null) }
            R1Log.i("Settings.signOut", "done")
            Toaster.show("Signed out")
            onAfter()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    companion object {
        fun factory(settings: SettingsRepository, tokens: TokenStore) = viewModelFactory {
            initializer { SettingsViewModel(settings = settings, tokens = tokens) }
        }
    }
}
