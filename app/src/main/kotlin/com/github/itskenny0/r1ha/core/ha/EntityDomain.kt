package com.github.itskenny0.r1ha.core.ha

enum class Domain(val prefix: String) {
    LIGHT("light"),
    FAN("fan"),
    COVER("cover"),
    MEDIA_PLAYER("media_player"),
    // ── on/off-only domains ─────────────────────────────────────────────────────────────
    // All four use the same `turn_on`/`turn_off` services and the same "on"/"off" state
    // strings, so they share most of the plumbing. Kept as separate enum values so the
    // card label, glyph, and accent can differ per domain (a smart-plug card shouldn't
    // read "AUTOMATION").
    SWITCH("switch"),
    INPUT_BOOLEAN("input_boolean"),
    AUTOMATION("automation"),
    /** Smart locks — uses `lock.lock` / `lock.unlock` services; state is "locked"/"unlocked". */
    LOCK("lock"),
    /** Humidifiers + dehumidifiers — scalar `target_humidity` (0-100) via `set_humidity`. */
    HUMIDIFIER("humidifier"),
    /**
     * Thermostats. State is the HVAC mode ("off"/"heat"/"cool"/"auto"/…) rather than
     * "on"/"off", so isOn computation has a domain-specific branch (see DefaultHaRepository).
     * Currently exposed as a switch-only card — wheel turns the entity on/off via
     * climate.turn_on / climate.turn_off (which restores the previous HVAC mode). Driving
     * `target_temperature` from the wheel would need min_temp/max_temp from attrs to scale
     * percent into the temperature range, which is a refactor beyond the time budget.
     */
    CLIMATE("climate"),
    // ── Action-only domains ─────────────────────────────────────────────────────────────
    // No persistent on/off state, no scalar; just a "fire" trigger. Rendered as ActionCard
    // instead of SwitchCard / scalar card. Wheel input is ignored on these — they're
    // tap-only. The "state" of these entities is mostly a last-fired timestamp in HA;
    // scripts add an "on" state while running, the others stay stateless.
    SCENE("scene"),
    SCRIPT("script"),
    BUTTON("button"),
    ;

    /** Action-only domains — UI renders them as fire-and-forget ActionCard tiles. */
    val isAction: Boolean get() = this == SCENE || this == SCRIPT || this == BUTTON

    companion object {
        private val byPrefix = entries.associateBy { it.prefix }
        fun fromPrefix(prefix: String): Domain =
            byPrefix[prefix] ?: throw IllegalArgumentException("unknown domain prefix: '$prefix'")
        fun isSupportedPrefix(prefix: String): Boolean = prefix in byPrefix
    }
}
