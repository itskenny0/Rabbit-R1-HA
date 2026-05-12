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
                // Climate is rendered as a switch in this release; any wheel input maps to
                // power on/off until target-temperature scaling lands.
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
            }
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
    }
}
