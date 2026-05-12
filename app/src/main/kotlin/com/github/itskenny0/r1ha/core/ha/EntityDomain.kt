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
    /**
     * HA helper buttons — identical service shape to [BUTTON] (`input_button.press`),
     * fire-and-forget. Common in YAML dashboards as one-tap shortcuts that automations
     * react to. Rendered as ActionCard.
     */
    INPUT_BUTTON("input_button"),
    /**
     * Read-only sensors — temperature, humidity, power, etc. State is the reading itself,
     * `unit_of_measurement` from attributes is the suffix. No wheel input, no tap action;
     * rendered by SensorCard as a big numeric readout.
     */
    SENSOR("sensor"),
    /**
     * Binary sensors — door open/closed, motion detected, leak alarm. State is "on"/"off"
     * (HA convention: "on" = the affordance is triggered, "off" = quiet). Same SensorCard
     * variant as `sensor` but rendered as a binary state word + device-class label rather
     * than a numeric reading. Read-only.
     */
    BINARY_SENSOR("binary_sensor"),
    /**
     * `number` entities — MQTT-common, exposes a settable numeric scalar with explicit
     * `min` / `max` / `step` attributes. Many MQTT-Discovery integrations land here
     * (volume knobs, temperature setpoints that don't fit climate, pump speeds, etc.).
     * Service: `number.set_value` with `{value: <float>}`.
     */
    NUMBER("number"),
    /** Same as [NUMBER] but lives in HA's helpers (`input_number.X`). */
    INPUT_NUMBER("input_number"),
    /**
     * Valve entities — similar shape to covers (open/close/position/stop) but separate
     * domain so HA can distinguish water valves from window covers. Services mirror
     * cover (`open_valve`, `close_valve`, `set_valve_position`, `stop_valve`).
     */
    VALVE("valve"),
    /**
     * Robot vacuums. State is one of cleaning / docked / returning / paused / idle /
     * error. Services: vacuum.start, vacuum.stop, vacuum.pause, vacuum.return_to_base.
     * Rendered as a switch card with the state word visible — tap toggles
     * start ↔ return-to-base which is the natural "send the robot home" / "send it
     * out" intent users have on a card.
     */
    VACUUM("vacuum"),
    /**
     * Water heaters — same scalar shape as climate: target_temperature within
     * min_temp..max_temp. Services: water_heater.set_temperature, water_heater.turn_on,
     * water_heater.turn_off. Reuses the climate dispatch path.
     */
    WATER_HEATER("water_heater"),
    ;

    /** Action-only domains — UI renders them as fire-and-forget ActionCard tiles. */
    val isAction: Boolean get() =
        this == SCENE || this == SCRIPT || this == BUTTON || this == INPUT_BUTTON

    /** Read-only sensor domains — UI renders them as SensorCard. No wheel, no tap. */
    val isSensor: Boolean get() = this == SENSOR || this == BINARY_SENSOR

    companion object {
        private val byPrefix = entries.associateBy { it.prefix }
        fun fromPrefix(prefix: String): Domain =
            byPrefix[prefix] ?: throw IllegalArgumentException("unknown domain prefix: '$prefix'")
        fun isSupportedPrefix(prefix: String): Boolean = prefix in byPrefix
    }
}
