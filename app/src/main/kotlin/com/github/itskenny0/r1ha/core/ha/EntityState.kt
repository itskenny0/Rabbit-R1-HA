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
    /**
     * Light-specific: HA `supported_color_modes` list (e.g. ["onoff"], ["brightness"],
     * ["brightness", "color_temp"], ["brightness", "color_temp", "hs"]). Used to decide
     * which wheel-mode chips the card surface — a non-CCT bulb shouldn't offer a CT
     * mode toggle. Empty for non-light entities.
     */
    val supportedColorModes: List<String> = emptyList(),
    /**
     * Light-specific: current colour temperature in kelvin, if the bulb is in
     * color_temp mode. Used as the starting position when the user switches the wheel
     * into CT mode and as the displayed value.
     */
    val colorTempK: Int? = null,
    /** Light-specific: HA min_color_temp_kelvin attribute. */
    val minColorTempK: Int? = null,
    /** Light-specific: HA max_color_temp_kelvin attribute. */
    val maxColorTempK: Int? = null,
    /**
     * Light-specific: current hue in degrees (0..360), if the bulb is in colour mode.
     * Read from `hs_color` attribute's first element. Null if the bulb isn't currently
     * in a colour mode, even though it might support one.
     */
    val hue: Double? = null,
    /**
     * Native step granularity for the entity's settable scalar. `number` / `input_number`
     * report this directly (`step` attribute, e.g. 0.1 / 1 / 5). We carry it through so
     * the VM can snap wheel-derived values to multiples of step before the service call —
     * sending "42.7341" to an entity whose step is 1 just gets silently rounded by HA
     * anyway, but doing the rounding ourselves also keeps the displayed value honest.
     */
    val step: Double? = null,
    /**
     * Light effect list — HA's `effect_list` attribute. Empty when the bulb doesn't
     * support effects (most plain RGB bulbs). When non-empty, the card surfaces a small
     * effect-cycle chip that lets the user pick from the available effects.
     */
    val effectList: List<String> = emptyList(),
    /** Currently-active effect from `effect`. Used by the card to highlight which chip
     *  in the cycle is active. */
    val effect: String? = null,
    /**
     * Full raw attributes JSON from HA, kept so the customize dialog's DETAILS section
     * can list every attribute the entity reports — useful for diagnosing weird MQTT
     * payloads, exploring undocumented integrations, and verifying that the app's
     * specific-field parsers (color_temp_kelvin, supported_color_modes, etc.) are
     * picking up the right values. Null when we constructed this EntityState without
     * a source JSON object (e.g. in tests).
     */
    val attributesJson: kotlinx.serialization.json.JsonObject? = null,
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
