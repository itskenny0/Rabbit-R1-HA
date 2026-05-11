package com.github.itskenny0.r1ha.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(
    context: Context,
    datastoreName: String = "r1ha_settings",
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(datastoreName) },
    )

    private object K {
        val serverUrl = stringPreferencesKey("server.url")
        val haVersion = stringPreferencesKey("server.ha_version")
        val favorites = stringPreferencesKey("favorites")

        val wheelStep = intPreferencesKey("wheel.step")
        val wheelAccel = booleanPreferencesKey("wheel.accel")
        val wheelInvert = booleanPreferencesKey("wheel.invert")
        val wheelKeySource = stringPreferencesKey("wheel.key_source")

        val uiDisplayMode = stringPreferencesKey("ui.display_mode")
        val uiShowPill = booleanPreferencesKey("ui.show_pill")
        val uiShowArea = booleanPreferencesKey("ui.show_area")
        val uiShowDots = booleanPreferencesKey("ui.show_dots")

        val behaviorHaptics = booleanPreferencesKey("behavior.haptics")
        val behaviorKeepOn = booleanPreferencesKey("behavior.keep_on")
        val behaviorTapToggle = booleanPreferencesKey("behavior.tap_toggle")

        val theme = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            val url = p[K.serverUrl]
            val server = url?.let { ServerConfig(url = it, haVersion = p[K.haVersion]) }
            AppSettings(
                server = server,
                favorites = p[K.favorites]?.takeIf { it.isNotBlank() }?.split('\n').orEmpty(),
                wheel = WheelSettings(
                    stepPercent = (p[K.wheelStep] ?: 5).coerceIn(1, 10),
                    acceleration = p[K.wheelAccel] ?: true,
                    invertDirection = p[K.wheelInvert] ?: false,
                    keySource = p[K.wheelKeySource]?.let { runCatching { WheelKeySource.valueOf(it) }.getOrNull() } ?: WheelKeySource.AUTO,
                ),
                ui = UiOptions(
                    displayMode = p[K.uiDisplayMode]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: DisplayMode.PERCENT,
                    showOnOffPill = p[K.uiShowPill] ?: true,
                    showAreaLabel = p[K.uiShowArea] ?: true,
                    showPositionDots = p[K.uiShowDots] ?: true,
                ),
                behavior = Behavior(
                    haptics = p[K.behaviorHaptics] ?: true,
                    keepScreenOn = p[K.behaviorKeepOn] ?: true,
                    tapToToggle = p[K.behaviorTapToggle] ?: true,
                ),
                theme = p[K.theme]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.PRAGMATIC_HYBRID,
            )
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = currentBlocking()
        val next = transform(current)
        store.edit { p ->
            next.server?.let { p[K.serverUrl] = it.url; it.haVersion?.let { v -> p[K.haVersion] = v } } ?: run {
                p.remove(K.serverUrl); p.remove(K.haVersion)
            }
            p[K.favorites] = next.favorites.joinToString("\n")
            p[K.wheelStep] = next.wheel.stepPercent
            p[K.wheelAccel] = next.wheel.acceleration
            p[K.wheelInvert] = next.wheel.invertDirection
            p[K.wheelKeySource] = next.wheel.keySource.name
            p[K.uiDisplayMode] = next.ui.displayMode.name
            p[K.uiShowPill] = next.ui.showOnOffPill
            p[K.uiShowArea] = next.ui.showAreaLabel
            p[K.uiShowDots] = next.ui.showPositionDots
            p[K.behaviorHaptics] = next.behavior.haptics
            p[K.behaviorKeepOn] = next.behavior.keepScreenOn
            p[K.behaviorTapToggle] = next.behavior.tapToToggle
            p[K.theme] = next.theme.name
        }
    }

    private suspend fun currentBlocking(): AppSettings = settings.first()
}
