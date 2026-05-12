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
