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
        // sensor/binary_sensor/scene/script/button are still outside the supported set —
        // they need separate UI affordances (read-only display or fire-and-forget action
        // tiles) before the wheel-driven card stack can host them sensibly.
        assertThrows<IllegalArgumentException> { EntityId("sensor.foo") }
        assertThrows<IllegalArgumentException> { EntityId("scene.movie_night") }
    }

    @Test fun `parses new domains`() {
        assertThat(EntityId("switch.desk_lamp").domain).isEqualTo(Domain.SWITCH)
        assertThat(EntityId("input_boolean.guest_mode").domain).isEqualTo(Domain.INPUT_BOOLEAN)
        assertThat(EntityId("automation.morning_routine").domain).isEqualTo(Domain.AUTOMATION)
        assertThat(EntityId("lock.front_door").domain).isEqualTo(Domain.LOCK)
        assertThat(EntityId("humidifier.bedroom").domain).isEqualTo(Domain.HUMIDIFIER)
    }
}
