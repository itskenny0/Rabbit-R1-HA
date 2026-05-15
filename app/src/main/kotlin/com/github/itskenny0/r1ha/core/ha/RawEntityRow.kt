package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Raw `/api/states` row for domains we don't model in the [Domain]
 * enum — cameras, persons, weather, calendars, climate-like helpers.
 * Adding these to [Domain] would force exhaustive-when updates across
 * 5+ files for read-only or special-handling entities that don't fit
 * the card-stack tile model anyway, so we keep the lightweight shape
 * here and let dedicated screens (CamerasScreen, WeatherScreen, etc.)
 * decode the attributes they care about.
 *
 * @Stable so Compose can skip recomposition when a row reference
 * hasn't changed across a refresh.
 */
@Stable
data class RawEntityRow(
    /** Full HA entity_id e.g. "camera.front_door". */
    val entityId: String,
    /** Friendly name from `attributes.friendly_name`, falling back to
     *  the entity_id when HA didn't include one. */
    val friendlyName: String,
    /** HA's raw state string ("idle", "home", "rainy", etc.). */
    val state: String,
    /** Full attributes JSON — caller picks out the fields it cares
     *  about (e.g. cameras read `entity_picture`, weather reads
     *  `temperature` + `condition`). */
    val attributes: JsonObject,
    /** When HA last reported a state change for this entity. Decoded
     *  from the `last_changed` field on `/api/states`; null when HA
     *  didn't include one or the timestamp was unparseable. Used by
     *  the Persons screen + similar surfaces to show 'since X'
     *  relative timestamps. */
    val lastChanged: Instant? = null,
)
