package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

class FavoritesPickerViewModel(
    private val repo: HaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class Row(val state: EntityState, val isFavorite: Boolean, val orderIndex: Int?)
    data class UiState(val loading: Boolean = true, val rows: List<Row> = emptyList(), val error: String? = null)

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
                    entitiesCache = list
                    _ui.value = UiState(loading = false, rows = buildRows(list, favs))
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

    /** Build the row list from cached entities + the current favourites list. Favourites are
     *  pinned to the top of the list in user-chosen order so the up/down arrows produce
     *  visible movement; non-favourites follow alphabetically. */
    private fun buildRows(entities: List<EntityState>, favs: List<String>): List<Row> {
        val favOrder = favs.withIndex().associate { (idx, id) -> id to idx }
        val byId = entities.associateBy { it.id.value }
        // Favourites first, in the order the user set.
        val favouriteRows = favs.mapNotNull { id ->
            byId[id]?.let { Row(it, isFavorite = true, orderIndex = favOrder[id]) }
        }
        // Then everything else, alphabetical.
        val otherRows = entities
            .filter { it.id.value !in favOrder }
            .sortedBy { it.friendlyName.lowercase() }
            .map { Row(it, isFavorite = false, orderIndex = null) }
        return favouriteRows + otherRows
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
            _ui.value = _ui.value.copy(rows = buildRows(entitiesCache, newFavs))
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
            _ui.value = _ui.value.copy(rows = buildRows(entitiesCache, newFavs))
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
            _ui.value = _ui.value.copy(rows = buildRows(entitiesCache, newFavs))
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
