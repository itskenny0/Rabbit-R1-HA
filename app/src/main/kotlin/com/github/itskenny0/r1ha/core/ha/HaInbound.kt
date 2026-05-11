package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface HaInbound {

    @Serializable @SerialName("auth_required")
    data class AuthRequired(@SerialName("ha_version") val haVersion: String? = null) : HaInbound

    @Serializable @SerialName("auth_ok")
    data class AuthOk(@SerialName("ha_version") val haVersion: String? = null) : HaInbound

    @Serializable @SerialName("auth_invalid")
    data class AuthInvalid(val message: String? = null) : HaInbound

    @Serializable @SerialName("result")
    data class Result(
        val id: Int,
        val success: Boolean,
        val result: JsonElement? = null,
        val error: Error? = null,
    ) : HaInbound {
        @Serializable
        data class Error(val code: String? = null, val message: String? = null)
    }

    @Serializable @SerialName("event")
    data class Event(val id: Int, val event: EventBody) : HaInbound {
        @Serializable
        data class EventBody(val variables: Variables)
        @Serializable
        data class Variables(val trigger: Trigger)
        @Serializable
        data class Trigger(
            val platform: String,
            @SerialName("entity_id") val entityId: String,
            @SerialName("from_state") val fromState: StateBlock? = null,
            @SerialName("to_state") val toState: StateBlock,
        )
        @Serializable
        data class StateBlock(
            @SerialName("entity_id") val entityId: String? = null,
            val state: String,
            val attributes: JsonObject = JsonObject(emptyMap()),
            @SerialName("last_changed") val lastChanged: String? = null,
        )
    }

    @Serializable @SerialName("pong")
    data class Pong(val id: Int? = null) : HaInbound

    /** Catch-all for frames we don't model — keeps the parser non-fatal. */
    @Serializable @SerialName("__unknown__")
    data object Unknown : HaInbound
}
