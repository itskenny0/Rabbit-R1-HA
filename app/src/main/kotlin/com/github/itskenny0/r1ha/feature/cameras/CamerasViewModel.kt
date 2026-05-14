package com.github.itskenny0.r1ha.feature.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Cameras surface. Pulls every `camera.*` entity HA reports
 * via [HaRepository.listRawEntitiesByDomain], extracts the friendly
 * name + state ("idle" / "recording" / "streaming"), and exposes the
 * list to [CamerasScreen] for tap-to-view.
 *
 * The actual snapshot polling lives in
 * [com.github.itskenny0.r1ha.ui.components.CameraSnapshot]; this VM
 * just holds the directory.
 */
class CamerasViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Camera(
        val entityId: String,
        val name: String,
        /** HA-reported state: usually "idle" / "recording" / "streaming" /
         *  "unavailable". Surfaced as a small chip on each row. */
        val state: String,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val cameras: List<Camera> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("camera").fold(
                onSuccess = { rows ->
                    val list = rows.map { Camera(it.entityId, it.friendlyName, it.state) }
                        .sortedBy { it.name.lowercase() }
                    R1Log.i("Cameras", "loaded ${list.size}")
                    _ui.value = _ui.value.copy(loading = false, cameras = list, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Cameras", "list failed: ${t.message}")
                    Toaster.error("Cameras load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { CamerasViewModel(haRepository) }
        }
    }
}
