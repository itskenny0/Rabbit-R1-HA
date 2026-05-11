package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

class HaOutboundTest {
    @Test fun `auth has no id and serialises token`() {
        val s = HaJson.encodeToString(HaOutbound.serializer(), HaOutbound.Auth(accessToken = "TOK"))
        assertThat(s).contains("\"type\":\"auth\"")
        assertThat(s).contains("\"access_token\":\"TOK\"")
        assertThat(s).doesNotContain("\"id\":")
    }
    @Test fun `ping carries id`() {
        val s = HaJson.encodeToString(HaOutbound.serializer(), HaOutbound.Ping(id = 9))
        assertThat(s).contains("\"id\":9")
        assertThat(s).contains("\"type\":\"ping\"")
    }
    @Test fun `subscribe_trigger lists entities`() {
        val s = HaJson.encodeToString(
            HaOutbound.serializer(),
            HaOutbound.SubscribeStateTrigger(id = 2, entityIds = listOf("light.a", "fan.b")),
        )
        assertThat(s).contains("\"type\":\"subscribe_trigger\"")
        assertThat(s).contains("\"platform\":\"state\"")
        assertThat(s).contains("\"entity_id\":[\"light.a\",\"fan.b\"]")
    }
    @Test fun `call_service includes target and data`() {
        val data = buildJsonObject { put("brightness_pct", JsonPrimitive(50)) }
        val s = HaJson.encodeToString(
            HaOutbound.serializer(),
            HaOutbound.CallService(id = 3, haDomain = "light", service = "turn_on", entityId = "light.kitchen", data = data),
        )
        assertThat(s).contains("\"type\":\"call_service\"")
        assertThat(s).contains("\"domain\":\"light\"")
        assertThat(s).contains("\"service\":\"turn_on\"")
        assertThat(s).contains("\"target\":{\"entity_id\":\"light.kitchen\"}")
        assertThat(s).contains("\"service_data\":{\"brightness_pct\":50}")
    }
    @Test fun `unsubscribe_events references previous id`() {
        val s = HaJson.encodeToString(
            HaOutbound.serializer(),
            HaOutbound.UnsubscribeEvents(id = 11, subscription = 2),
        )
        assertThat(s).contains("\"type\":\"unsubscribe_events\"")
        assertThat(s).contains("\"subscription\":2")
    }
}
