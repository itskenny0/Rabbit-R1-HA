package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Filter chip — groups related domains into a single user-facing label. Picked so that the
 * chip row stays readable on a 240 px display (six or seven chips, not fifteen).
 */
enum class PickerFilter(val label: String, val matches: (Domain) -> Boolean) {
    ALL("ALL", { true }),
    FAVS("★ FAVS", { true }),  // "isFavorite" filter applied outside `matches`; this entry is special-cased.
    LIGHTS("LIGHTS", { it == Domain.LIGHT }),
    SWITCHES("SWITCHES", { it == Domain.SWITCH || it == Domain.INPUT_BOOLEAN || it == Domain.AUTOMATION }),
    COVERS("COVERS", { it == Domain.COVER }),
    CLIMATE("CLIMATE", { it == Domain.CLIMATE || it == Domain.HUMIDIFIER || it == Domain.FAN }),
    LOCKS("LOCKS", { it == Domain.LOCK }),
    MEDIA("MEDIA", { it == Domain.MEDIA_PLAYER }),
    SCENES("SCENES", { it.isAction }),
    SENSORS("SENSORS", { it.isSensor }),
}

class FavoritesPickerViewModel(
    private val repo: HaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class Row(val state: EntityState, val isFavorite: Boolean, val orderIndex: Int?)
    data class UiState(
        val loading: Boolean = true,
        val rows: List<Row> = emptyList(),
        val error: String? = null,
        val filter: PickerFilter = PickerFilter.ALL,
        /** Total counts per filter chip — surfaces a small number next to each chip so
         *  the user can see at a glance how many entities of each kind are available
         *  even before tapping the chip. */
        val countsByFilter: Map<PickerFilter, Int> = emptyMap(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    /** Cached list of all controllable entities from the latest /api/states fetch. Toggling or
     *  reordering favourites doesn't change this list, so we can update [_ui] locally without
     *  re-fetching every time the user taps a checkbox. */
    private var entitiesCache: List<EntityState> = emptyList()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val snapshot = settings.settings.first()
            R1Log.i("FavoritesPicker.refresh", "server=${snapshot.server?.url ?: "null"} favoritesSoFar=${snapshot.favorites.size}")
            val all = repo.listAllEntities()
            val favs = snapshot.favorites
            all.fold(
                onSuccess = { list ->
                    // Keep BOTH scalar-controllable and on/off-only entities — on/off ones
                    // render as a switch card on CardStack (wheel up/down flips them, tap
                    // toggles) rather than being hidden entirely.
                    entitiesCache = list
                    val cur = _ui.value
                    _ui.value = cur.copy(
                        loading = false,
                        rows = buildRows(list, favs, cur.filter),
                        countsByFilter = countsByFilter(list, favs),
                    )
                    R1Log.i("FavoritesPicker.refresh", "fetched ${list.size} entities")
                },
                onFailure = {
                    R1Log.e("FavoritesPicker.refresh", "fetch failed", it)
                    Toaster.show("Fetch failed: ${it.message}", long = true)
                    _ui.value = UiState(loading = false, error = it.message)
                },
            )
        }
    }

    /** Switch the active filter chip. Re-evaluates [buildRows] against the cached entity
     *  set — no network refetch needed, just a local prune. */
    fun setFilter(filter: PickerFilter) {
        val cur = _ui.value
        if (cur.filter == filter) return
        viewModelScope.launch {
            val favs = settings.settings.first().favorites
            _ui.value = cur.copy(
                filter = filter,
                rows = buildRows(entitiesCache, favs, filter),
            )
        }
    }

    /** Build the row list from cached entities + the current favourites list. Sorted by name
     *  rather than favourites-first; toggling a checkbox no longer reorders the list, which
     *  prevents the visible page from jumping when the user is selecting several entities
     *  back-to-back. The up/down arrows still mutate favourites order — visible in CardStack. */
    private fun buildRows(entities: List<EntityState>, favs: List<String>, filter: PickerFilter): List<Row> {
        val favOrder = favs.withIndex().associate { (idx, id) -> id to idx }
        return entities
            .asSequence()
            .filter { ent ->
                when (filter) {
                    PickerFilter.ALL -> true
                    PickerFilter.FAVS -> ent.id.value in favOrder
                    else -> filter.matches(ent.id.domain)
                }
            }
            .sortedBy { it.friendlyName.lowercase() }
            .map {
                Row(it, isFavorite = it.id.value in favOrder, orderIndex = favOrder[it.id.value])
            }
            .toList()
    }

    /** Tally how many entities match each filter — surfaces as a small badge on each chip
     *  so the user knows at a glance which filters are populated. Computed once per refresh
     *  rather than per-render so chip layout stays cheap. */
    private fun countsByFilter(entities: List<EntityState>, favs: List<String>): Map<PickerFilter, Int> {
        val favSet = favs.toSet()
        return PickerFilter.entries.associateWith { f ->
            when (f) {
                PickerFilter.ALL -> entities.size
                PickerFilter.FAVS -> entities.count { it.id.value in favSet }
                else -> entities.count { f.matches(it.id.domain) }
            }
        }
    }

    fun toggle(entityId: String) {
        viewModelScope.launch {
            // Read-modify-write must happen INSIDE settings.update so the SettingsRepository
            // mutex serialises concurrent toggles; otherwise two rapid taps could each capture
            // the pre-tap favourites and overwrite each other's change.
            var newFavs: List<String> = emptyList()
            settings.update { cur ->
                val l = cur.favorites.toMutableList()
                if (entityId in l) l.remove(entityId) else l.add(entityId)
                newFavs = l
                cur.copy(favorites = l)
            }
            // Local re-render without re-fetching the entity list.
            val cur = _ui.value
            _ui.value = cur.copy(
                rows = buildRows(entitiesCache, newFavs, cur.filter),
                countsByFilter = countsByFilter(entitiesCache, newFavs),
            )
        }
    }

    fun moveUp(entityId: String) {
        viewModelScope.launch {
            var newFavs: List<String> = emptyList()
            settings.update { cur ->
                val l = cur.favorites.toMutableList()
                val idx = l.indexOf(entityId)
                if (idx > 0) { l.removeAt(idx); l.add(idx - 1, entityId) }
                newFavs = l
                cur.copy(favorites = l)
            }
            val cur = _ui.value
            _ui.value = cur.copy(rows = buildRows(entitiesCache, newFavs, cur.filter))
        }
    }

    fun moveDown(entityId: String) {
        viewModelScope.launch {
            var newFavs: List<String> = emptyList()
            settings.update { cur ->
                val l = cur.favorites.toMutableList()
                val idx = l.indexOf(entityId)
                if (idx in 0 until l.size - 1) { l.removeAt(idx); l.add(idx + 1, entityId) }
                newFavs = l
                cur.copy(favorites = l)
            }
            val cur = _ui.value
            _ui.value = cur.copy(rows = buildRows(entitiesCache, newFavs, cur.filter))
        }
    }

    companion object {
        fun factory(
            repo: HaRepository,
            settings: SettingsRepository,
        ) = viewModelFactory {
            initializer {
                FavoritesPickerViewModel(repo = repo, settings = settings)
            }
        }
    }
}
