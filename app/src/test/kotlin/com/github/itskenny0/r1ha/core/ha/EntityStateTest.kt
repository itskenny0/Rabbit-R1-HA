package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EntityStateTest {
    private fun base(id: String, on: Boolean = true) = EntityState(
        id = EntityId(id), friendlyName = "n", area = null, isOn = on,
        percent = null, raw = null, lastChanged = Instant.EPOCH, isAvailable = true,
    )

    @Test fun `light brightness 0-255 normalises to 0-100`() {
        assertThat(EntityState.normaliseLightBrightness(0)).isEqualTo(0)
        assertThat(EntityState.normaliseLightBrightness(128)).isEqualTo(50)
        assertThat(EntityState.normaliseLightBrightness(255)).isEqualTo(100)
        assertThat(EntityState.normaliseLightBrightness(1)).isEqualTo(0)   // rounds-to-nearest
        assertThat(EntityState.normaliseLightBrightness(2)).isEqualTo(1)
    }

    @Test fun `media volume 0_0-1_0 normalises to 0-100`() {
        assertThat(EntityState.normaliseMediaVolume(0.0)).isEqualTo(0)
        assertThat(EntityState.normaliseMediaVolume(0.5)).isEqualTo(50)
        assertThat(EntityState.normaliseMediaVolume(1.0)).isEqualTo(100)
        assertThat(EntityState.normaliseMediaVolume(0.001)).isEqualTo(0)
    }

    @Test fun `fan and cover are already 0-100 and pass through`() {
        assertThat(EntityState.normaliseFanPercentage(73)).isEqualTo(73)
        assertThat(EntityState.normaliseCoverPosition(0)).isEqualTo(0)
    }

    @Test fun `denormalise light pct to raw`() {
        assertThat(EntityState.lightRawFromPct(0)).isEqualTo(0)
        assertThat(EntityState.lightRawFromPct(50)).isEqualTo(128)
        assertThat(EntityState.lightRawFromPct(100)).isEqualTo(255)
    }
}
