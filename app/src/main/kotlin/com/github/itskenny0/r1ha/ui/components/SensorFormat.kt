package com.github.itskenny0.r1ha.ui.components

import java.util.Locale

/**
 * Render a raw HA state string for display. If the string parses as a number, round to
 * at most two decimal places and strip trailing zeros so "21.74321" → "21.74", "21.70" →
 * "21.7", and "21.00" → "21". Non-numeric states (e.g. an enum sensor reporting "Heating"
 * or a binary sensor reporting "on") pass through unchanged.
 *
 * Locale-pinned to US so we always parse and emit dot-separated decimals; HA's REST
 * surface uses dot separators regardless of the server's display locale, and the R1
 * device locale isn't always reliable.
 *
 * Used by [SensorCard] for the body readout and the chart-axis labels, and by any other
 * surface that wants to show a raw HA reading without floating-point noise.
 */
fun formatSensorValue(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val num = raw.toDoubleOrNull() ?: return raw
    // Filter out NaN / Infinity — those should never come from HA but we'd rather show a
    // dash than the literal "NaN" string in a numeric readout.
    if (num.isNaN() || num.isInfinite()) return "—"
    // %.2f rounds half-away-from-zero in the JVM, which is the natural mental model
    // (21.745 → "21.75"). Strip trailing zeros + dot to keep the readout tight.
    val rounded = "%.2f".format(Locale.US, num)
    return rounded.trimEnd('0').trimEnd('.')
}
