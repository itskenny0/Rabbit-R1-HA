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
            }
        }

        fun tapAction(target: EntityId, isOn: Boolean): ServiceCall = when (target.domain) {
            Domain.LIGHT, Domain.FAN -> ServiceCall(target, if (isOn) "turn_off" else "turn_on", JsonObject(emptyMap()))
            // For covers, `isOn` here means "currently open". Toggle to the opposite end of
            // travel — close if open, open if closed/stopped/in-motion. (Sending open_cover
            // while the cover is already opening is a no-op on HA's side.)
            Domain.COVER -> ServiceCall(target, if (isOn) "close_cover" else "open_cover", JsonObject(emptyMap()))
            Domain.MEDIA_PLAYER -> ServiceCall(target, "media_play_pause", JsonObject(emptyMap()))
        }

        /**
         * Explicit on/off (not toggle) for switch-card entities — the wheel needs to set an
         * absolute state, not flip it. For media players we use `media_play`/`media_pause`
         * because HA's `media_play_pause` is the toggle equivalent; the explicit variants
         * give us deterministic behaviour from the wheel.
         */
        fun setSwitch(target: EntityId, on: Boolean): ServiceCall = when (target.domain) {
            Domain.LIGHT, Domain.FAN -> ServiceCall(
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
        }
    }
}
