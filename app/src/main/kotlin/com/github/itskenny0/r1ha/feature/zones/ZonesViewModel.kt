package com.github.itskenny0.r1ha.feature.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the Zones surface — HA's zone registry presented as a list
 * of "where is everyone right now". Each row:
 *   - zone name + a count of persons currently inside
 *   - the list of persons/devices in that zone
 *   - the zone's lat/lon (for orientation) + radius
 *
 * Powered by `/api/states` for both `zone.*` and `person.*` /
 * `device_tracker.*`. The matching is done by comparing the person's
 * `state` attribute (which is the zone friendly_name when inside one,
 * `not_home` otherwise) to each zone's friendly_name — this matches
 * HA's own resolution rule.
 */
class ZonesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Zone(
        val entityId: String,
        val name: String,
        val latitude: Double?,
        val longitude: Double?,
        val radiusMeters: Double?,
        /** Names of persons / device-trackers currently inside this
         *  zone (their `state` attribute equals this zone's name). */
        val occupants: List<String>,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val zones: List<Zone> = emptyList(),
        /** Persons whose state is "not_home" (or any other zone name we
         *  didn't find) — surfaced under an OUTSIDE bucket so they're
         *  not invisible just because no zone matches. */
        val outside: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Two parallel fetches — zones AND person/device_tracker.
            // /api/states returns the full registry per domain.
            val zoneJob = async { haRepository.listRawEntitiesByDomain("zone") }
            val personJob = async { haRepository.listRawEntitiesByDomain("person") }
            val trackerJob = async { haRepository.listRawEntitiesByDomain("device_tracker") }
            awaitAll(zoneJob, personJob, trackerJob)
            val zoneRes = zoneJob.await()
            val personRes = personJob.await()
            val trackerRes = trackerJob.await()
            if (zoneRes.isFailure) {
                val t = zoneRes.exceptionOrNull()
                R1Log.w("Zones", "zone load failed: ${t?.message}")
                Toaster.error("Zones load failed: ${t?.message ?: "unknown"}")
                _ui.value = _ui.value.copy(loading = false, error = t?.message)
                return@launch
            }
            val zoneRows = zoneRes.getOrNull().orEmpty()
            val peopleRows = personRes.getOrNull().orEmpty()
            val trackerRows = trackerRes.getOrNull().orEmpty()

            // Build a name→occupants map by matching each person/tracker's
            // state to a zone's friendly_name. HA's standard rule is
            // exact-match on friendly_name; we keep that contract here.
            val occupantsByZone = HashMap<String, MutableList<String>>()
            val outside = mutableListOf<String>()
            (peopleRows + trackerRows).forEach { row ->
                val state = row.state
                when {
                    state.equals("home", ignoreCase = true) -> {
                        // 'home' = whichever zone HA marked as
                        // is_home (typically zone.home). We attach
                        // them to that zone in the second pass below.
                        occupantsByZone.getOrPut("home") { mutableListOf() }.add(row.friendlyName)
                    }
                    state.equals("not_home", ignoreCase = true) ||
                        state.equals("away", ignoreCase = true) ||
                        state.equals("unknown", ignoreCase = true) ||
                        state.equals("unavailable", ignoreCase = true) ->
                        outside.add(row.friendlyName)
                    else -> {
                        occupantsByZone.getOrPut(state) { mutableListOf() }.add(row.friendlyName)
                    }
                }
            }

            val zones = zoneRows.map { row ->
                val name = row.friendlyName
                val attrs = row.attributes
                val lat = (attrs["latitude"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val lon = (attrs["longitude"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val radius = (attrs["radius"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val isHome = (attrs["is_home"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
                // Pick up exact-name matches plus, for the home zone,
                // anyone whose state is 'home' (HA's special-case for
                // the configured home location).
                val occupants = (occupantsByZone[name] ?: emptyList()).toMutableList()
                if (isHome) occupants += (occupantsByZone["home"] ?: emptyList())
                Zone(
                    entityId = row.entityId,
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = radius,
                    occupants = occupants.distinct(),
                )
            }.sortedByDescending { it.occupants.size }

            R1Log.i(
                "Zones",
                "zones=${zones.size} outside=${outside.size}",
            )
            _ui.value = _ui.value.copy(
                loading = false,
                zones = zones,
                outside = outside.distinct(),
                error = null,
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ZonesViewModel(haRepository) }
        }
    }
}
