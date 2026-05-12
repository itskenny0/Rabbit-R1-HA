package com.github.itskenny0.r1ha.core.prefs

enum class ThemeId { MINIMAL_DARK, PRAGMATIC_HYBRID, COLORFUL_CARDS }

enum class DisplayMode { PERCENT, RAW }

/** What the wheel keycodes actually arrive as on this device. */
enum class WheelKeySource { AUTO, DPAD, VOLUME }

data class WheelSettings(
    val stepPercent: Int = 2,           // 1, 2, 5, or 10
    val acceleration: Boolean = true,
    val invertDirection: Boolean = false,
    val keySource: WheelKeySource = WheelKeySource.AUTO,
)

data class UiOptions(
    val displayMode: DisplayMode = DisplayMode.PERCENT,
    val showOnOffPill: Boolean = true,
    val showAreaLabel: Boolean = true,
    val showPositionDots: Boolean = true,
    /** Number of recent state-change entries shown on text/categorical SensorCard history. */
    val textHistoryLength: Int = 20,
)

data class Behavior(
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val tapToToggle: Boolean = true,
    /**
     * When on, the Android system status bar is hidden across the app via the
     * WindowInsetsController. Off by default — the bar is harmless and gives the user
     * a clock + battery for free. Useful when running on an R1 LineageOS GSI where the
     * bar competes with our chrome row for the precious top 24 dp.
     */
    val hideStatusBar: Boolean = false,
)

data class ServerConfig(
    val url: String,
    val haVersion: String? = null,
)

data class AppSettings(
    val server: ServerConfig? = null,
    val favorites: List<String> = emptyList(),
    val wheel: WheelSettings = WheelSettings(),
    val ui: UiOptions = UiOptions(),
    val behavior: Behavior = Behavior(),
    val theme: ThemeId = ThemeId.PRAGMATIC_HYBRID,
    /**
     * Client-side display-name overrides keyed by entity_id. When present, the UI prefers
     * this label to HA's `friendly_name` for that entity. Persistent (lives in DataStore)
     * but never synced back to HA — the override is local-only so users can disambiguate
     * "Office light strip front" vs "back" without touching their HA setup.
     */
    val nameOverrides: Map<String, String> = emptyMap(),
    /** Per-entity card customization (text scale, visibility toggles, long-press action).
     *  Independent of [nameOverrides] so the rename feature (shipped earlier) keeps its
     *  storage format untouched. */
    val entityOverrides: Map<String, EntityOverride> = emptyMap(),
)
