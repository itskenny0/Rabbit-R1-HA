package com.github.itskenny0.r1ha.feature.scenes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Drives the Scenes & Scripts launcher. Pulls the full HA entity list
 * (the same REST call the favourites picker uses) and surfaces every
 * `scene.*` / `script.*` as a tappable row.
 *
 * Fire dispatch is the same `ServiceCall` shape the card stack uses —
 * scene activation is `scene.turn_on`, script execution is
 * `script.turn_on` (HA accepts that as an alias for the per-script
 * `script.<id>` service, with the bonus that we don't have to derive a
 * service name from the entity id). Failures get force-shown via
 * [Toaster.error] because firing a scene is an intentional action and
 * the user needs to know if it didn't land.
 */
class ScenesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Kind { SCENE, SCRIPT }

    /** Filter chip selection. */
    enum class Filter { ALL, SCENES, SCRIPTS }

    @androidx.compose.runtime.Stable
    data class Entry(
        val id: EntityId,
        val name: String,
        val kind: Kind,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        /** All loaded entries (full set); [entries] applies kind + query filters. */
        val all: List<Entry> = emptyList(),
        val filter: Filter = Filter.ALL,
        /** Free-text search query — substring-matched against the entry name
         *  and entity_id, case-insensitive. Empty = no text filter. */
        val query: String = "",
        /** Per-filter counts for the chip labels. ALL is the full size. */
        val counts: Map<Filter, Int> = emptyMap(),
        /** True while the "All Lights Off" master action is in flight; the
         *  button disables itself to prevent double-tap re-fires. */
        val allLightsOffInFlight: Boolean = false,
    ) {
        /** Subset visible under the current filter + search query. Counts are
         *  small (typically <50) so the in-place filter is trivial. */
        val entries: List<Entry> get() {
            val byKind = when (filter) {
                Filter.ALL -> all
                Filter.SCENES -> all.filter { it.kind == Kind.SCENE }
                Filter.SCRIPTS -> all.filter { it.kind == Kind.SCRIPT }
            }
            if (query.isBlank()) return byKind
            val q = query.trim().lowercase()
            return byKind.filter {
                it.name.lowercase().contains(q) || it.id.value.lowercase().contains(q)
            }
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            haRepository.listAllEntities().fold(
                onSuccess = { entities ->
                    val entries = entities.mapNotNull { es ->
                        val kind = when (es.id.domain) {
                            Domain.SCENE -> Kind.SCENE
                            Domain.SCRIPT -> Kind.SCRIPT
                            else -> null
                        } ?: return@mapNotNull null
                        Entry(id = es.id, name = es.friendlyName, kind = kind)
                    }.sortedBy { it.name.lowercase() }
                    val counts = mapOf(
                        Filter.ALL to entries.size,
                        Filter.SCENES to entries.count { it.kind == Kind.SCENE },
                        Filter.SCRIPTS to entries.count { it.kind == Kind.SCRIPT },
                    )
                    R1Log.i(
                        "Scenes",
                        "loaded scenes=${counts[Filter.SCENES]} scripts=${counts[Filter.SCRIPTS]}",
                    )
                    _ui.value = UiState(
                        loading = false,
                        all = entries,
                        filter = _ui.value.filter,
                        counts = counts,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Scenes", "list failed: ${t.message}")
                    Toaster.error("Scenes load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false)
                },
            )
        }
    }

    fun setFilter(filter: Filter) {
        if (_ui.value.filter == filter) return
        _ui.value = _ui.value.copy(filter = filter)
    }

    fun setQuery(query: String) {
        if (_ui.value.query == query) return
        _ui.value = _ui.value.copy(query = query)
    }

    /**
     * Surface the entity_id + a hint at how to wire this into an HA
     * automation. Useful when the user wants to reference a script from
     * Node-RED, an automation YAML, or a webhook. Long-press is the
     * non-destructive gesture (tap fires the entity) so it's the right
     * place for an information affordance.
     */
    fun showDetail(entry: Entry) {
        val short = entry.id.value
        val full = buildString {
            append(entry.name).append('\n')
            append(entry.id.value).append('\n')
            append(
                when (entry.kind) {
                    Kind.SCENE -> "service: scene.turn_on"
                    Kind.SCRIPT -> "service: script.turn_on"
                },
            )
        }
        Toaster.showExpandable(shortText = short, fullText = full)
    }

    /**
     * Master "all lights off" — dispatches `light.turn_off` with no target,
     * which HA treats as "every entity in the light domain". Same trick
     * HA's own frontend dashboards use for the "All Lights Off" tile.
     * Other domains have their own master variants (`switch.turn_off`,
     * `media_player.media_pause`) we could add later; lights are the
     * most-requested mass-action by far.
     *
     * Why this lives on the Scenes screen: it's the same conceptual
     * surface — fire-and-forget mass actions. A user reaching for
     * "all lights off" is in the same flow as reaching for a "bedtime"
     * scene; putting them together saves a navigation hop.
     */
    fun allLightsOff() {
        if (_ui.value.allLightsOffInFlight) return
        _ui.value = _ui.value.copy(allLightsOffInFlight = true)
        viewModelScope.launch {
            // Construct a domain-wide call by targeting any light entity
            // we know of and overriding the target afterwards. Cheaper than
            // adding a "no-target" branch to ServiceCall.
            val anyLight = haRepository.listAllEntities().getOrNull()
                ?.firstOrNull { it.id.domain == Domain.LIGHT }
                ?.id
            if (anyLight == null) {
                Toaster.show("No light entities — nothing to turn off")
                _ui.value = _ui.value.copy(allLightsOffInFlight = false)
                return@launch
            }
            // The ServiceCall plumbing wants a target. We pass anyLight, but
            // include `entity_id: "all"` in the data payload — HA accepts
            // either form and prefers the data field when set.
            val call = ServiceCall(
                target = anyLight,
                service = "turn_off",
                data = kotlinx.serialization.json.buildJsonObject {
                    put("entity_id", kotlinx.serialization.json.JsonPrimitive("all"))
                },
            )
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Scenes", "all lights off dispatched")
                    Toaster.show("All lights off")
                },
                onFailure = { t ->
                    R1Log.w("Scenes", "all lights off failed: ${t.message}")
                    Toaster.error("All-off failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(allLightsOffInFlight = false)
        }
    }

    /**
     * Fire a scene or script. Both use `turn_on` with no payload — HA
     * treats `scene.turn_on` / `script.turn_on` as the activation
     * service for these action-only domains.
     */
    fun fire(entry: Entry) {
        viewModelScope.launch {
            val call = ServiceCall(
                target = entry.id,
                service = "turn_on",
                data = JsonObject(emptyMap()),
            )
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Scenes", "fired ${entry.id.value}")
                    Toaster.show("Fired '${entry.name}'")
                },
                onFailure = { t ->
                    R1Log.w("Scenes", "fire ${entry.id.value} failed: ${t.message}")
                    Toaster.error("Fire failed: ${t.message ?: "unknown"}")
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ScenesViewModel(haRepository) }
        }
    }
}
