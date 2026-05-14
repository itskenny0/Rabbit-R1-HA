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
    /**
     * Select / input_select-only: the full list of available options from HA's
     * `options` attribute. Empty for non-select entities. The wheel cycles through
     * these and the picker overlay lists them all for one-tap selection.
     */
    val selectOptions: List<String> = emptyList(),
    /**
     * Select / input_select-only: the currently-selected option, equal to HA's `state`
     * for these domains. Null only when the state is unknown/unavailable.
     */
    val currentOption: String? = null,
    /** Media-player-only: now-playing track title. Null when idle / off. */
    val mediaTitle: String? = null,
    /** Media-player-only: now-playing artist name. */
    val mediaArtist: String? = null,
    /** Media-player-only: now-playing album. */
    val mediaAlbumName: String? = null,
    /** Media-player-only: total media duration in seconds. */
    val mediaDuration: Int? = null,
    /**
     * Media-player-only: last-reported playback position in seconds, anchored at
     * [mediaPositionUpdatedAt]. To get a live position, interpolate against the
     * wall-clock time since the anchor — that's how HA's own dashboards do it.
     */
    val mediaPosition: Int? = null,
    /** Media-player-only: when [mediaPosition] was last reported. */
    val mediaPositionUpdatedAt: Instant? = null,
    /**
     * Media-player-only: HA `entity_picture` attribute. Typically a relative path
     * like `/api/media_player_proxy/media_player.X?token=…` (HA's proxied art); can
     * also be an absolute URL or a `data:` URI for some integrations. Card renders
     * these inline via [com.github.itskenny0.r1ha.ui.components.AsyncBitmap].
     */
    val mediaPicture: String? = null,
    /** Media-player-only: current mute state. Needed so the MUTE button can toggle
     *  (HA's `volume_mute` service requires an explicit `is_volume_muted` value). */
    val isVolumeMuted: Boolean = false,
    /**
     * Media-player-only: `supported_features` bitmask as advertised by the
     * integration. The card uses this to gate transport buttons — calling
     * `media_next_track` on a player that doesn't advertise [MediaPlayerFeature.NEXT_TRACK]
     * makes HA reject with a 'Validation error: Entity X doesn't support service Y'
     * message, so we'd rather not show the button at all. 0 (unknown / unset)
     * falls back to showing every button for backward compatibility — the user
     * can still see a useful UI even if the integration omits the bitmask.
     */
    val mediaSupportedFeatures: Int = 0,
) {
    /**
     * Subset of [MediaPlayerEntityFeature](https://github.com/home-assistant/core/blob/dev/homeassistant/components/media_player/const.py)
     * — only the bits the card actually gates buttons against. Defined here as
     * plain Int constants so the gating code stays readable (`hasFeature(NEXT_TRACK)`)
     * without dragging in a separate enum or pulling in HA's full constant list.
     */
    object MediaPlayerFeature {
        const val PAUSE = 1
        const val SEEK = 2
        const val VOLUME_SET = 4
        const val VOLUME_MUTE = 8
        const val PREVIOUS_TRACK = 16
        const val NEXT_TRACK = 32
        const val TURN_ON = 128
        const val TURN_OFF = 256
        const val PLAY_MEDIA = 512
        const val VOLUME_STEP = 1024
        const val STOP = 4096
        const val PLAY = 16384
    }

    /** Convenience: does this entity advertise the given [MediaPlayerFeature] bit? */
    fun hasMediaFeature(featureBit: Int): Boolean =
        mediaSupportedFeatures != 0 && (mediaSupportedFeatures and featureBit) != 0

    companion object {
        fun normaliseLightBrightness(raw: Int): Int = ((raw.coerceIn(0, 255)) * 100.0 / 255.0).roundToInt()
        fun normaliseMediaVolume(raw: Double): Int = (raw.coerceIn(0.0, 1.0) * 100.0).roundToInt()
        fun normaliseFanPercentage(raw: Int): Int = raw.coerceIn(0, 100)
        fun normaliseCoverPosition(raw: Int): Int = raw.coerceIn(0, 100)
        fun lightRawFromPct(pct: Int): Int = (pct.coerceIn(0, 100) * 255.0 / 100.0).roundToInt()
        fun mediaVolumeFromPct(pct: Int): Double = pct.coerceIn(0, 100) / 100.0
    }
}
