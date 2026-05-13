package com.github.itskenny0.r1ha.core.prefs

enum class ThemeId { MINIMAL_DARK, PRAGMATIC_HYBRID, COLORFUL_CARDS }

enum class DisplayMode { PERCENT, RAW }

/**
 * Display unit for temperature readouts. AUTO follows HA's reported unit
 * (`temperature_unit` attribute on climate entities, defaults to Celsius); CELSIUS and
 * FAHRENHEIT force the display + conversion regardless of HA's setting.
 */
enum class TemperatureUnit { AUTO, CELSIUS, FAHRENHEIT }

/** What the wheel keycodes actually arrive as on this device. */
enum class WheelKeySource { AUTO, DPAD, VOLUME }

/**
 * Shape of the acceleration curve when `wheel.acceleration` is on. The wheel rate (in
 * events/sec) gets folded through the matching slope to produce a step multiplier;
 * SUBTLE keeps the boost small for precise dimming, AGGRESSIVE goes hard so a fast
 * spin can cross the full 0..100 range in a couple of detents. MEDIUM is the previous
 * behaviour (1 + excess*0.5 above 4 ev/s).
 */
enum class AccelerationCurve { SUBTLE, MEDIUM, AGGRESSIVE }

data class WheelSettings(
    val stepPercent: Int = 2,           // 1, 2, 5, or 10
    val acceleration: Boolean = true,
    val invertDirection: Boolean = false,
    val keySource: WheelKeySource = WheelKeySource.AUTO,
    /** Slope of the acceleration curve when [acceleration] is on. */
    val accelerationCurve: AccelerationCurve = AccelerationCurve.MEDIUM,
)

data class UiOptions(
    val displayMode: DisplayMode = DisplayMode.PERCENT,
    val showOnOffPill: Boolean = true,
    val showAreaLabel: Boolean = true,
    val showPositionDots: Boolean = true,
    /** Number of recent state-change entries shown on text/categorical SensorCard history. */
    val textHistoryLength: Int = 20,
    /**
     * When on, the chrome row at the top of the card stack draws a solid background so
     * the previous card's tail-end can't peek through into the chrome area. On by
     * default — most users wanted a clean transition rather than a "deck of cards"
     * look. Off restores the original transparent-chrome behaviour where the previous
     * card is visible under the chrome.
     */
    val hideCardTailAbove: Boolean = true,
    /** Max decimal places shown for numeric sensor readings; 0 = integer, 2 = default. */
    val maxDecimalPlaces: Int = 2,
    /** Force-display temperature unit; AUTO follows HA's native unit. Default Celsius. */
    val tempUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    /**
     * When on, the card stack wraps — wheeling/swiping past the last card lands on the
     * first, and vice versa. Off by default so a user can tell when they've reached the
     * end of their list. The action-card overscroll-to-fire gesture still wins at the
     * top boundary regardless of this setting.
     */
    val infiniteScroll: Boolean = false,
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
    /**
     * When on (the default), scrolling the wheel on a non-scalar card (lock,
     * cover-without-position, vacuum, plain switch) flips it on/off — wheel-up =
     * on, wheel-down = off. Earlier versions flipped this to off after one user
     * report of accidental fires, but a follow-up made it clear that the wheel
     * toggling switches was the intended behaviour — the accidental-fire concern
     * was actually about action cards (scenes / scripts / buttons), which have
     * their own no-wheel guard. Users who DO want the wheel inert on switch cards
     * (so a brush doesn't relock a door) can still turn this off in
     * Settings → Behaviour.
     */
    val wheelTogglesSwitches: Boolean = true,
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
