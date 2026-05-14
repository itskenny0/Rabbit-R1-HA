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
        /** All loaded entries (full set); [visibleEntries] applies the chip filter. */
        val all: List<Entry> = emptyList(),
        val filter: Filter = Filter.ALL,
        /** Per-filter counts for the chip labels. ALL is the full size. */
        val counts: Map<Filter, Int> = emptyMap(),
    ) {
        /** Subset visible under the current filter — computed each read so we
         *  don't have to invalidate it from two places when filter or all
         *  change. The list count is small (typically <50) so the filter is
         *  trivial. */
        val entries: List<Entry> get() = when (filter) {
            Filter.ALL -> all
            Filter.SCENES -> all.filter { it.kind == Kind.SCENE }
            Filter.SCRIPTS -> all.filter { it.kind == Kind.SCRIPT }
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
