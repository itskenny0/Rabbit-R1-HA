package com.github.itskenny0.r1ha.ui.components

import java.util.Locale

/**
 * Render a raw HA state string for display. If the string parses as a number, round to
 * [maxDecimals] decimal places and strip trailing zeros so "21.74321" → "21.74", "21.70"
 * → "21.7", and "21.00" → "21". Non-numeric states (e.g. an enum sensor reporting
 * "Heating" or a binary sensor reporting "on") pass through unchanged.
 *
 * Locale-pinned to US so we always parse and emit dot-separated decimals; HA's REST
 * surface uses dot separators regardless of the server's display locale, and the R1
 * device locale isn't always reliable.
 *
 * [maxDecimals] is the user's configurable cap (global UiOptions setting + future
 * per-card override). Clamped to [0, 6] — beyond 6 the rounding doesn't add information,
 * it just makes the readout fight for screen space.
 */
fun formatSensorValue(raw: String?, maxDecimals: Int = 2): String {
    if (raw.isNullOrBlank()) return "—"
    val num = raw.toDoubleOrNull() ?: return raw
    // Filter out NaN / Infinity — those should never come from HA but we'd rather show a
    // dash than the literal "NaN" string in a numeric readout.
    if (num.isNaN() || num.isInfinite()) return "—"
    val places = maxDecimals.coerceIn(0, 6)
    val rounded = "%.${places}f".format(Locale.US, num)
    return if (places == 0) rounded else rounded.trimEnd('0').trimEnd('.')
}
