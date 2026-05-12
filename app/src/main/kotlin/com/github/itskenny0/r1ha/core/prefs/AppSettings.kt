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
)

data class Behavior(
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val tapToToggle: Boolean = true,
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
)
