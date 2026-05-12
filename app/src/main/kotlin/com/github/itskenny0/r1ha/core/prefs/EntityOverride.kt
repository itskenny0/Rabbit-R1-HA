package com.github.itskenny0.r1ha.core.prefs

/**
 * Per-card client-side customization. Each field is nullable so the absence of an
 * override means "fall through to the global setting" — that way a brand new card picks
 * up whatever UI option the user has set, but a card the user has customized stays
 * customized even when global options change.
 *
 * Stored alongside (not inside) the [AppSettings.nameOverrides] map. Names live in their
 * own map because that field shipped earlier and renaming it would force a migration of
 * users' existing renames; this struct adds the rest of the customizable surface.
 */
data class EntityOverride(
    /**
     * Visual size multiplier for the card's big readout (the percent number, the ON/OFF
     * word, the sensor value). 1.0 = default. Constrained on the way out to a small set
     * of presets (0.7 / 0.85 / 1.0 / 1.15 / 1.3) — letting the user type an arbitrary
     * float would defeat the segmented-picker UX and risk weird edge cases.
     */
    val textScale: Float = 1.0f,
    /** Per-card override for [UiOptions.showOnOffPill]; null = inherit global. */
    val showOnOffPill: Boolean? = null,
    /** Per-card override for [UiOptions.showAreaLabel]; null = inherit global. */
    val showAreaLabel: Boolean? = null,
    /**
     * Entity to fire on long-press of this card. e.g. long-press a light card to trigger
     * a `scene.movie_night` or run a `script.bedtime`. Empty string = no long-press
     * action (the default). Validation: the entity_id must contain a "." and the
     * prefix must be a domain we know how to dispatch (anything supported, basically).
     */
    val longPressTarget: String? = null,
    /**
     * Per-card override for [UiOptions.maxDecimalPlaces]. Null = inherit global. Range
     * 0..6; 0 means "no decimals, integer only" which is useful for power meters and the
     * like where a fractional watt is just noise. Only relevant for sensor entities; the
     * customize dialog hides the picker for non-sensors.
     */
    val maxDecimalPlaces: Int? = null,
    /**
     * Per-card accent colour as an ARGB int, null = inherit the domain-derived accent.
     * The accent flows through to the card's domain-tab, the percent suffix, the switch
     * thumb when on, etc. Stored as Int rather than Color so the same encoding works in
     * preferences without needing a separate serializer.
     */
    val accentColor: Int? = null,
) {
    companion object {
        /** Allowed text-scale steps. Picker offers a chip per step. */
        val TEXT_SCALES = listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)

        /** Curated palette for the per-card accent picker. Hand-picked to feel cohesive
         *  on the near-black background — no neon, no muddy mid-tones. Names track the
         *  R1 design vocabulary where possible (Warm = stock orange). */
        val ACCENT_PALETTE: List<Pair<String, Int>> = listOf(
            "WARM" to 0xFFF36F21.toInt(),
            "COOL" to 0xFF41BDF5.toInt(),
            "GREEN" to 0xFF52C77F.toInt(),
            "NEUTRAL" to 0xFFB0B0B0.toInt(),
            "RED" to 0xFFE53935.toInt(),
            "AMBER" to 0xFFFFB300.toInt(),
            "VIOLET" to 0xFFB388FF.toInt(),
            "PINK" to 0xFFFF6F91.toInt(),
            "CYAN" to 0xFF26C6DA.toInt(),
        )

        val NONE = EntityOverride()
    }
}
