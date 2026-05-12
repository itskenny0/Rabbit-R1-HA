package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ServiceCallTest {
    @Test fun `light setPct 0 maps to turn_off`() {
        val call = ServiceCall.setPercent(EntityId("light.kitchen"), 0)
        assertThat(call.service).isEqualTo("turn_off")
        assertThat(call.data).isEqualTo(JsonObject(emptyMap()))
    }
    @Test fun `light setPct 50 maps to turn_on with brightness_pct`() {
        val call = ServiceCall.setPercent(EntityId("light.kitchen"), 50)
        assertThat(call.service).isEqualTo("turn_on")
        assertThat(call.data["brightness_pct"]).isEqualTo(JsonPrimitive(50))
    }
    @Test fun `fan setPct 0 maps to turn_off`() {
        val call = ServiceCall.setPercent(EntityId("fan.bedroom"), 0)
        assertThat(call.service).isEqualTo("turn_off")
    }
    @Test fun `fan setPct 30 maps to set_percentage`() {
        val call = ServiceCall.setPercent(EntityId("fan.bedroom"), 30)
        assertThat(call.service).isEqualTo("set_percentage")
        assertThat(call.data["percentage"]).isEqualTo(JsonPrimitive(30))
    }
    @Test fun `cover setPct maps to set_cover_position`() {
        val call = ServiceCall.setPercent(EntityId("cover.shade"), 75)
        assertThat(call.service).isEqualTo("set_cover_position")
        assertThat(call.data["position"]).isEqualTo(JsonPrimitive(75))
    }
    @Test fun `media setPct maps to volume_set with float`() {
        val call = ServiceCall.setPercent(EntityId("media_player.kitchen"), 40)
        assertThat(call.service).isEqualTo("volume_set")
        // 0.40 with rounding; compare numeric value rather than literal
        val v = call.data["volume_level"]!!.toString().toDouble()
        assertThat(v).isWithin(0.001).of(0.40)
    }
    @Test fun `tap action varies by domain`() {
        assertThat(ServiceCall.tapAction(EntityId("light.x"), isOn = true).service).isEqualTo("turn_off")
        assertThat(ServiceCall.tapAction(EntityId("light.x"), isOn = false).service).isEqualTo("turn_on")
        // Cover tap toggles to the opposite extreme rather than just stopping — `stop_cover`
        // on a stationary cover was a no-op which felt broken from the user's perspective.
        assertThat(ServiceCall.tapAction(EntityId("cover.x"), isOn = true).service).isEqualTo("close_cover")
        assertThat(ServiceCall.tapAction(EntityId("cover.x"), isOn = false).service).isEqualTo("open_cover")
        assertThat(ServiceCall.tapAction(EntityId("media_player.x"), isOn = true).service).isEqualTo("media_play_pause")
    }
}
