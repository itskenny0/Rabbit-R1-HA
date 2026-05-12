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
        // sensor/binary_sensor are read-only displays — nothing for the wheel/tap to do.
        // weather/device_tracker similarly read-only state surfaces.
        assertThrows<IllegalArgumentException> { EntityId("sensor.living_room_temperature") }
        assertThrows<IllegalArgumentException> { EntityId("binary_sensor.front_door") }
        assertThrows<IllegalArgumentException> { EntityId("weather.home") }
    }

    @Test fun `parses on-off and scalar domains`() {
        assertThat(EntityId("switch.desk_lamp").domain).isEqualTo(Domain.SWITCH)
        assertThat(EntityId("input_boolean.guest_mode").domain).isEqualTo(Domain.INPUT_BOOLEAN)
        assertThat(EntityId("automation.morning_routine").domain).isEqualTo(Domain.AUTOMATION)
        assertThat(EntityId("lock.front_door").domain).isEqualTo(Domain.LOCK)
        assertThat(EntityId("humidifier.bedroom").domain).isEqualTo(Domain.HUMIDIFIER)
        assertThat(EntityId("climate.living_room").domain).isEqualTo(Domain.CLIMATE)
    }

    @Test fun `parses action-only domains`() {
        assertThat(EntityId("scene.movie_night").domain).isEqualTo(Domain.SCENE)
        assertThat(EntityId("script.morning_lights").domain).isEqualTo(Domain.SCRIPT)
        assertThat(EntityId("button.doorbell").domain).isEqualTo(Domain.BUTTON)
    }

    @Test fun `isAction flags action-only domains`() {
        assertThat(Domain.SCENE.isAction).isTrue()
        assertThat(Domain.SCRIPT.isAction).isTrue()
        assertThat(Domain.BUTTON.isAction).isTrue()
        assertThat(Domain.LIGHT.isAction).isFalse()
        assertThat(Domain.SWITCH.isAction).isFalse()
    }
}
