package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EntityIdTest {
    @Test fun `parses prefix into Domain`() {
        val id = EntityId("light.kitchen")
        assertThat(id.domain).isEqualTo(Domain.LIGHT)
        assertThat(id.objectId).isEqualTo("kitchen")
    }

    @Test fun `media_player keeps underscore`() {
        val id = EntityId("media_player.living_room")
        assertThat(id.domain).isEqualTo(Domain.MEDIA_PLAYER)
        assertThat(id.objectId).isEqualTo("living_room")
    }

    @Test fun `rejects missing dot`() {
        assertThrows<IllegalArgumentException> { EntityId("light_kitchen") }
    }

    @Test fun `rejects unsupported domain`() {
        assertThrows<IllegalArgumentException> { EntityId("automation.foo") }
    }
}
