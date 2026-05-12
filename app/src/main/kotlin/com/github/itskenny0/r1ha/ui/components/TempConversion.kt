package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.prefs.TemperatureUnit

/**
 * Display-side temperature conversion. HA reports temperatures in whatever unit the user
 * configured server-side (usually °C in metric regions, °F in the US); we surface the
 * user's preference and convert at the display layer.
 *
 * Returns (value-in-target-unit, suffix-string). The suffix string includes the degree
 * symbol so callers can drop it straight onto the readout's unit field.
 */
fun convertTemperature(value: Double, fromUnit: String?, target: TemperatureUnit): Pair<Double, String> {
    // Normalise HA's unit string into a single-char tag. HA usually sends "°C" / "°F",
    // sometimes "C" / "F", sometimes nothing. Default to C — safer assumption for the
    // app's primary target market and matches what HA core defaults to.
    val from = when {
        fromUnit == null -> 'C'
        fromUnit.contains('F', ignoreCase = true) -> 'F'
        else -> 'C'
    }
    val to = when (target) {
        TemperatureUnit.AUTO -> from
        TemperatureUnit.CELSIUS -> 'C'
        TemperatureUnit.FAHRENHEIT -> 'F'
    }
    val converted = when {
        from == to -> value
        from == 'F' && to == 'C' -> (value - 32.0) * 5.0 / 9.0
        from == 'C' && to == 'F' -> value * 9.0 / 5.0 + 32.0
        else -> value
    }
    return converted to "°$to"
}
