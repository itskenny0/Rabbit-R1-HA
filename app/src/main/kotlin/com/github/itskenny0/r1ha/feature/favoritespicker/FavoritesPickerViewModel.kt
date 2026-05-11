package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
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

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val all = repo.listAllEntities()
            val favs = settings.settings.first().favorites
            all.fold(
                onSuccess = { list ->
                    val favOrder = favs.withIndex().associate { (idx, id) -> id to idx }
                    val rows = list.sortedBy { it.friendlyName.lowercase() }.map {
                        Row(it, isFavorite = it.id.value in favOrder, orderIndex = favOrder[it.id.value])
                    }
                    _ui.value = UiState(loading = false, rows = rows)
                },
                onFailure = { _ui.value = UiState(loading = false, error = it.message) },
            )
        }
    }

    fun toggle(entityId: String) {
        viewModelScope.launch {
            settings.update { cur ->
                val l = cur.favorites.toMutableList()
                if (entityId in l) l.remove(entityId) else l += entityId
                cur.copy(favorites = l)
            }
            refresh()
        }
    }

    fun moveUp(entityId: String) {
        viewModelScope.launch {
            settings.update { cur ->
                val l = cur.favorites.toMutableList()
                val idx = l.indexOf(entityId)
                if (idx > 0) { l.removeAt(idx); l.add(idx - 1, entityId) }
                cur.copy(favorites = l)
            }
            refresh()
        }
    }

    fun moveDown(entityId: String) {
        viewModelScope.launch {
            settings.update { cur ->
                val l = cur.favorites.toMutableList()
                val idx = l.indexOf(entityId)
                if (idx in 0 until l.size - 1) { l.removeAt(idx); l.add(idx + 1, entityId) }
                cur.copy(favorites = l)
            }
            refresh()
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
