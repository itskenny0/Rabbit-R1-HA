package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A concrete HA service call: which `domain.service` + the `service_data` JSON. Target is the entity. */
data class ServiceCall(
    val target: EntityId,
    val service: String,
    val data: JsonObject,
) {
    val haDomain: String get() = target.domain.prefix

    companion object {
        fun setPercent(target: EntityId, pct: Int): ServiceCall {
            val clamped = pct.coerceIn(0, 100)
            return when (target.domain) {
                Domain.LIGHT -> if (clamped == 0)
                    ServiceCall(target, "turn_off", JsonObject(emptyMap()))
                else
                    ServiceCall(target, "turn_on", buildJsonObject { put("brightness_pct", JsonPrimitive(clamped)) })

                Domain.FAN -> if (clamped == 0)
                    ServiceCall(target, "turn_off", JsonObject(emptyMap()))
                else
                    ServiceCall(target, "set_percentage", buildJsonObject { put("percentage", JsonPrimitive(clamped)) })

                Domain.COVER ->
                    ServiceCall(target, "set_cover_position", buildJsonObject { put("position", JsonPrimitive(clamped)) })

                Domain.MEDIA_PLAYER ->
                    ServiceCall(target, "volume_set", buildJsonObject {
                        put("volume_level", JsonPrimitive(EntityState.mediaVolumeFromPct(clamped)))
                    })

                // Humidifier — `humidity` is already 0..100 in HA, no normalisation needed.
                // We also auto-turn-on at the start of the wheel turn (clamped > 0) so the
                // user doesn't have to engage the device with a separate tap before setting
                // the target. clamped == 0 turns it off.
                Domain.HUMIDIFIER -> if (clamped == 0)
                    ServiceCall(target, "turn_off", JsonObject(emptyMap()))
                else
                    ServiceCall(target, "set_humidity", buildJsonObject { put("humidity", JsonPrimitive(clamped)) })

                // Pure on/off domains shouldn't hit setPercent at all — the wheel routes
                // them through setSwitch in the VM. Defensive default: any non-zero percent
                // = on, zero = off.
                Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION -> ServiceCall(
                    target,
                    if (clamped == 0) "turn_off" else "turn_on",
                    JsonObject(emptyMap()),
                )
                Domain.LOCK -> ServiceCall(
                    target,
                    if (clamped == 0) "lock" else "unlock",
                    JsonObject(emptyMap()),
                )
                // Climate is now rendered as scalar when the entity exposes a
                // temperature range — but this path is only entered for the fallback
                // (no range / TARGET_TEMPERATURE not supported), so on/off is correct.
                // The proper scalar path uses [setTemperature] with the converted Celsius/
                // Fahrenheit value computed at the VM layer.
                Domain.CLIMATE -> ServiceCall(
                    target,
                    if (clamped == 0) "turn_off" else "turn_on",
                    JsonObject(emptyMap()),
                )
                // Action-only domains shouldn't reach setPercent — the wheel is ignored on
                // ActionCards. Defensive fallback: just fire the action.
                Domain.SCENE, Domain.SCRIPT -> ServiceCall(target, "turn_on", JsonObject(emptyMap()))
                Domain.BUTTON -> ServiceCall(target, "press", JsonObject(emptyMap()))
                // Sensors are read-only — the wheel and tap are no-ops at the VM level so
                // this branch shouldn't fire. Defensive: emit homeassistant.update_entity
                // which is the closest thing to "do something" without changing state, so
                // the call is loggable but harmless if anything ever does reach here.
                Domain.SENSOR, Domain.BINARY_SENSOR -> ServiceCall(
                    target,
                    "update_entity",
                    JsonObject(emptyMap()),
                )
                // Valve: same shape as cover; setPercent maps to set_valve_position.
                Domain.VALVE -> ServiceCall(
                    target,
                    "set_valve_position",
                    buildJsonObject { put("position", JsonPrimitive(clamped)) },
                )
                // Number / input_number: VM converts the wheel's percent into the
                // entity's native range and calls setNumberValue directly — this path
                // is the fallback (no range cached). Coerce 0..100 directly as the value.
                Domain.NUMBER, Domain.INPUT_NUMBER -> ServiceCall(
                    target,
                    "set_value",
                    buildJsonObject { put("value", JsonPrimitive(clamped)) },
                )
            }
        }

        /**
         * Number / input_number value setter. VM converts the wheel's 0..100 percent
         * into the entity's [EntityState.minRaw]/[EntityState.maxRaw] range and calls
         * this helper with the resolved value. Rounded to the nearest step where
         * possible at the call site; here we just emit whatever Double the VM gave us.
         */
        /**
         * Light colour-temp setter — `light.turn_on` with `color_temp_kelvin`. The VM
         * passes the kelvin value computed from the wheel's percent + the entity's
         * min/max range. Optionally includes a brightness so the bulb is guaranteed to
         * be on while it's being tinted; pass null to leave brightness untouched.
         */
        fun setLightColorTemp(target: EntityId, kelvin: Int, brightnessPct: Int? = null): ServiceCall =
            ServiceCall(
                target,
                "turn_on",
                buildJsonObject {
                    put("color_temp_kelvin", JsonPrimitive(kelvin.coerceAtLeast(1)))
                    if (brightnessPct != null) put("brightness_pct", JsonPrimitive(brightnessPct.coerceIn(0, 100)))
                },
            )

        /**
         * Light hue setter — `light.turn_on` with `hs_color: [hue, saturation]`. We pin
         * saturation at 100% so the wheel's hue scan goes through fully-saturated
         * colours; the user can de-saturate from HA if they want pastels. Bundles
         * brightness when supplied for the same reason as [setLightColorTemp].
         */
        fun setLightHue(target: EntityId, hueDegrees: Double, brightnessPct: Int? = null): ServiceCall {
            // Normalise hue into 0..360. The wheel can swing past those edges over time
            // so a defensive modular clamp keeps us inside HA's accepted range.
            val h = ((hueDegrees % 360.0) + 360.0) % 360.0
            return ServiceCall(
                target,
                "turn_on",
                buildJsonObject {
                    put(
                        "hs_color",
                        kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive(h))
                            add(JsonPrimitive(100))
                        },
                    )
                    if (brightnessPct != null) put("brightness_pct", JsonPrimitive(brightnessPct.coerceIn(0, 100)))
                },
            )
        }

        fun setNumberValue(target: EntityId, value: Double): ServiceCall {
            val rounded = (Math.round(value * 100.0) / 100.0)
            return ServiceCall(
                target,
                "set_value",
                buildJsonObject { put("value", JsonPrimitive(rounded)) },
            )
        }

        fun tapAction(target: EntityId, isOn: Boolean): ServiceCall = when (target.domain) {
            Domain.LIGHT, Domain.FAN -> ServiceCall(
                target,
                if (isOn) "turn_off" else "turn_on",
                JsonObject(emptyMap()),
            )
            // For covers, `isOn` here means "currently open". Toggle to the opposite end of
            // travel — close if open, open if closed/stopped/in-motion. (Sending open_cover
            // while the cover is already opening is a no-op on HA's side.)
            Domain.COVER -> ServiceCall(target, if (isOn) "close_cover" else "open_cover", JsonObject(emptyMap()))
            // Valve: same dispatch shape as cover, parallel service names.
            Domain.VALVE -> ServiceCall(target, if (isOn) "close_valve" else "open_valve", JsonObject(emptyMap()))
            Domain.MEDIA_PLAYER -> ServiceCall(target, "media_play_pause", JsonObject(emptyMap()))
            // Generic on/off — switch.foo, input_boolean.foo, automation.foo, humidifier.foo,
            // climate.foo all use the same turn_on/turn_off pair. For climate this restores
            // the previous HVAC mode (HA remembers it across off/on cycles).
            Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
            Domain.HUMIDIFIER, Domain.CLIMATE -> ServiceCall(
                target,
                if (isOn) "turn_off" else "turn_on",
                JsonObject(emptyMap()),
            )
            // Lock entity — `isOn` here means "unlocked". Tap to flip: lock if unlocked,
            // unlock if locked.
            Domain.LOCK -> ServiceCall(
                target,
                if (isOn) "lock" else "unlock",
                JsonObject(emptyMap()),
            )
            // Action entities — there's no "off" service. Tap always fires the trigger,
            // regardless of any (mostly meaningless) isOn state. Buttons use `press`,
            // scenes / scripts use `turn_on` to activate.
            Domain.SCENE, Domain.SCRIPT -> ServiceCall(target, "turn_on", JsonObject(emptyMap()))
            Domain.BUTTON -> ServiceCall(target, "press", JsonObject(emptyMap()))
            // Sensors are read-only — tapToggle shouldn't reach them (EntityCard skips the
            // tap modifier for sensor domains). Defensive: emit update_entity so any
            // accidental dispatch is at least a no-op refresh.
            Domain.SENSOR, Domain.BINARY_SENSOR -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
            // Number / input_number — there's no "toggle" semantics. Tap is mostly
            // dead code on these entities since the wheel does the work. Refresh.
            Domain.NUMBER, Domain.INPUT_NUMBER -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
        }

        /**
         * Explicit on/off (not toggle) for switch-card entities — the wheel needs to set an
         * absolute state, not flip it. For media players we use `media_play`/`media_pause`
         * because HA's `media_play_pause` is the toggle equivalent; the explicit variants
         * give us deterministic behaviour from the wheel.
         */
        fun setSwitch(target: EntityId, on: Boolean): ServiceCall = when (target.domain) {
            Domain.LIGHT, Domain.FAN, Domain.HUMIDIFIER, Domain.CLIMATE,
            Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION -> ServiceCall(
                target,
                if (on) "turn_on" else "turn_off",
                JsonObject(emptyMap()),
            )
            Domain.COVER -> ServiceCall(
                target,
                if (on) "open_cover" else "close_cover",
                JsonObject(emptyMap()),
            )
            Domain.VALVE -> ServiceCall(
                target,
                if (on) "open_valve" else "close_valve",
                JsonObject(emptyMap()),
            )
            Domain.NUMBER, Domain.INPUT_NUMBER -> ServiceCall(
                target,
                "set_value",
                buildJsonObject { put("value", JsonPrimitive(if (on) 100 else 0)) },
            )
            Domain.MEDIA_PLAYER -> ServiceCall(
                target,
                if (on) "media_play" else "media_pause",
                JsonObject(emptyMap()),
            )
            Domain.LOCK -> ServiceCall(
                target,
                if (on) "unlock" else "lock",
                JsonObject(emptyMap()),
            )
            // Action entities only have a "fire" service — there's no "off" equivalent for
            // a button press or a scene activation. The on-side of setSwitch is the fire
            // service; the off-side is a no-op (we just turn_on again, which is harmless).
            Domain.SCENE, Domain.SCRIPT -> ServiceCall(target, "turn_on", JsonObject(emptyMap()))
            Domain.BUTTON -> ServiceCall(target, "press", JsonObject(emptyMap()))
            // Sensors are read-only — defensive update_entity no-op.
            Domain.SENSOR, Domain.BINARY_SENSOR -> ServiceCall(
                target,
                "update_entity",
                JsonObject(emptyMap()),
            )
        }

        /**
         * Climate target-temperature setter. The VM converts the wheel's 0..100 percent
         * to a temperature using the entity's [EntityState.minRaw] / [EntityState.maxRaw]
         * range and calls this helper with the resolved value in the entity's native
         * temperature unit (°C or °F — HA picks based on the user's HA config, we don't
         * convert). Rounded to 1 decimal because most thermostats won't accept finer
         * resolution and 21.3 → 21.27 is just visual noise.
         */
        fun setTemperature(target: EntityId, temperature: Double): ServiceCall {
            val rounded = (Math.round(temperature * 10.0) / 10.0)
            return ServiceCall(
                target,
                "set_temperature",
                buildJsonObject { put("temperature", JsonPrimitive(rounded)) },
            )
        }
    }
}
