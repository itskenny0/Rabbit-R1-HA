package com.github.itskenny0.r1ha.core.ha

import java.time.Instant
import kotlin.math.roundToInt

data class EntityState(
    val id: EntityId,
    val friendlyName: String,
    val area: String?,
    val isOn: Boolean,
    /** 0..100 normalised value; null when entity is unavailable or has no scalar. */
    val percent: Int?,
    /** Domain-native raw value (brightness 0..255, percentage 0..100, volume_level 0..1 ×100, position 0..100). */
    val raw: Number?,
    val lastChanged: Instant,
    val isAvailable: Boolean,
    /**
     * Does HA expose a settable scalar for this entity? `false` for an on/off-only light
     * (color_mode = "onoff"), a fan without speed support, a cover without position support,
     * a media_player without VOLUME_SET. Used to keep on/off-only entities out of the
     * Favourites picker — there'd be nothing for the wheel to do.
     */
    val supportsScalar: Boolean = true,
    /**
     * Raw HA state string, in lower-case, kept verbatim from the wire. Required for any
     * decision that needs more granularity than the boolean [isOn] — most notably cover
     * tap-handling, which switches its service from open/close to stop_cover while the
     * cover is reporting `opening` / `closing`. Stored as-is so future domains that need
     * the same kind of branching (e.g. climate HVAC-mode display) don't need fresh wiring.
     */
    val rawState: String? = null,
    /**
     * `unit_of_measurement` from HA attrs — "°C", "%RH", "W", etc. Surfaces on SensorCard
     * as the suffix next to the reading; nullable because non-sensor entities (and some
     * sensors with no unit, e.g. enum sensors) don't have one.
     */
    val unit: String? = null,
    /**
     * `device_class` from HA attrs — "temperature", "humidity", "power", "motion", etc.
     * Used by SensorCard to pick an accent colour and the small label under the heading
     * so the user can tell a power sensor from a temperature sensor at a glance.
     */
    val deviceClass: String? = null,
    /**
     * Lower bound of the entity's settable scalar range, in the entity's native units.
     * Populated for climate (min_temp), humidifier (min_humidity), and any future domain
     * with a bounded setpoint. The CardStackViewModel uses this with [maxRaw] to map the
     * wheel's 0..100 percent into the right service-call value (e.g. 21 °C, not 21%).
     */
    val minRaw: Double? = null,
    /** Upper bound of [minRaw]'s range; same semantics. */
    val maxRaw: Double? = null,
) {
    companion object {
        fun normaliseLightBrightness(raw: Int): Int = ((raw.coerceIn(0, 255)) * 100.0 / 255.0).roundToInt()
        fun normaliseMediaVolume(raw: Double): Int = (raw.coerceIn(0.0, 1.0) * 100.0).roundToInt()
        fun normaliseFanPercentage(raw: Int): Int = raw.coerceIn(0, 100)
        fun normaliseCoverPosition(raw: Int): Int = raw.coerceIn(0, 100)
        fun lightRawFromPct(pct: Int): Int = (pct.coerceIn(0, 100) * 255.0 / 100.0).roundToInt()
        fun mediaVolumeFromPct(pct: Int): Double = pct.coerceIn(0, 100) / 100.0
    }
}
