package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.json.Json

/** kotlinx.serialization configuration for talking to Home Assistant. */
internal val HaJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "type"
}
