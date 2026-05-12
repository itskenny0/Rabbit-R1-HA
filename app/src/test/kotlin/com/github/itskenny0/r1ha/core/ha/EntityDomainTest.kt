package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EntityDomainTest {
    @Test fun `fromPrefix maps known prefixes`() {
        assertThat(Domain.fromPrefix("light")).isEqualTo(Domain.LIGHT)
        assertThat(Domain.fromPrefix("fan")).isEqualTo(Domain.FAN)
        assertThat(Domain.fromPrefix("cover")).isEqualTo(Domain.COVER)
        assertThat(Domain.fromPrefix("media_player")).isEqualTo(Domain.MEDIA_PLAYER)
        assertThat(Domain.fromPrefix("switch")).isEqualTo(Domain.SWITCH)
        assertThat(Domain.fromPrefix("input_boolean")).isEqualTo(Domain.INPUT_BOOLEAN)
        assertThat(Domain.fromPrefix("automation")).isEqualTo(Domain.AUTOMATION)
        assertThat(Domain.fromPrefix("lock")).isEqualTo(Domain.LOCK)
        assertThat(Domain.fromPrefix("humidifier")).isEqualTo(Domain.HUMIDIFIER)
    }

    @Test fun `fromPrefix rejects unknown prefix`() {
        // Domains the app deliberately doesn't surface — sensor/binary_sensor are read-
        // only, scene/script/button are fire-and-forget triggers (no state to drive). They
        // stay unsupported until the picker grows a separate Action-card variant.
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("sensor") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("scene") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("") }
    }
}
