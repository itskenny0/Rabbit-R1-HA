package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for the per-entity override encoding. The format is pipe-separated
 * URL-encoded scale|pill|area|longpress|decimals|accent|ct so a single typo would silently
 * lose user customizations across a save/load cycle. These tests pin the format and the
 * "missing trailing field" backward-compatibility path so we don't break older saves.
 */
class EntityOverrideCodecTest {

    @Test fun `empty map round-trips to empty string`() {
        val encoded = encodeEntityOverrides_visibleForTesting(emptyMap())
        assertThat(encoded).isEmpty()
        assertThat(decodeEntityOverrides_visibleForTesting(encoded)).isEmpty()
    }

    @Test fun `default override round-trips`() {
        val map = mapOf("light.kitchen" to EntityOverride.NONE)
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `fully-customised override round-trips through encoder`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                textScale = 1.15f,
                showOnOffPill = true,
                showAreaLabel = false,
                longPressTarget = "scene.movie_night",
                maxDecimalPlaces = 1,
                accentColor = 0xFF52C77F.toInt(),
                lightColorTempK = 2700,
            ),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `multiple entries round-trip with their own settings`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(textScale = 1.3f, accentColor = 0xFFE53935.toInt()),
            "switch.kettle" to EntityOverride(showOnOffPill = false, longPressTarget = "script.boil"),
            "sensor.outdoor_temp" to EntityOverride(maxDecimalPlaces = 0),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).isEqualTo(map)
    }

    @Test fun `longpress with URL-special characters survives encoding`() {
        // Entity IDs are alphanumeric + dot + underscore, but be defensive against future
        // HA additions (slashes, pipes) — the URL-encoding wrapping should handle them.
        val map = mapOf(
            "light.kitchen" to EntityOverride(longPressTarget = "scene.movie|night with spaces"),
        )
        val encoded = encodeEntityOverrides_visibleForTesting(map)
        // The pipe inside the longpress value would otherwise split the parts list and
        // corrupt the decode — URL-encoding to %7C makes it safe.
        assertThat(encoded).doesNotContain("night with spaces")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded["light.kitchen"]?.longPressTarget).isEqualTo("scene.movie|night with spaces")
    }

    @Test fun `line without equals is skipped`() {
        // A line with no `=` separator can't be parsed as id=value — the decoder skips it
        // rather than crashing. Lines WITH `=` but malformed value parts get tolerant
        // defaults applied (that's exercised by the legacy-save test below).
        val encoded = listOf(
            "garbage no equals sign",
            "valid.entity=1.0|?|?||?|?|?",
        ).joinToString("\n")
        val decoded = decodeEntityOverrides_visibleForTesting(encoded)
        assertThat(decoded).containsKey("valid.entity")
        assertThat(decoded).hasSize(1)
    }

    @Test fun `older save with fewer trailing fields still decodes`() {
        // Synthesize what a pre-CT save (before the lightColorTempK field shipped) would
        // have looked like — six pipe-separated parts instead of seven. The decoder
        // should treat the missing trailing field as inherit/null.
        val legacy = "light.kitchen=1.0|1|0|scene.foo|2|" + 0xFFF36F21.toInt()
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        val o = decoded["light.kitchen"]
        assertThat(o).isNotNull()
        assertThat(o!!.lightColorTempK).isNull()
        assertThat(o.accentColor).isEqualTo(0xFFF36F21.toInt())
        assertThat(o.showOnOffPill).isTrue()
        assertThat(o.showAreaLabel).isFalse()
    }
}
