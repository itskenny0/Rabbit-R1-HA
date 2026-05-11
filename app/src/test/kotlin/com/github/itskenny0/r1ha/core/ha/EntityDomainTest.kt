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
    }

    @Test fun `fromPrefix rejects unknown prefix`() {
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("automation") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("") }
    }
}
