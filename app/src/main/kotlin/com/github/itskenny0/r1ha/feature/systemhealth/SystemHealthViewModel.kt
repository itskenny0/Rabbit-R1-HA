package com.github.itskenny0.r1ha.feature.systemhealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaConfig
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the System Health (HA Server Info) screen. Composes
 * [HaRepository.fetchHaConfig] + [HaRepository.fetchErrorLog] in
 * parallel for the initial load; either failing is non-fatal — we
 * surface whichever we got. Power-user diagnostic, so we lean toward
 * verbose error messages.
 */
class SystemHealthViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val config: HaConfig? = null,
        val configError: String? = null,
        val errorLog: String = "",
        val errorLogError: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, configError = null, errorLogError = null)
            // Sequential is fine — /api/config returns in <50ms and the
            // error log is the slower one. Both hit the same HTTP client
            // so parallelism wouldn't buy much.
            val configResult = haRepository.fetchHaConfig()
            val errorLogResult = haRepository.fetchErrorLog()
            R1Log.i(
                "SystemHealth",
                "config=${configResult.isSuccess} errorLog=${errorLogResult.isSuccess}",
            )
            if (configResult.isFailure) {
                val msg = configResult.exceptionOrNull()?.message ?: "Config fetch failed"
                Toaster.error("Config: $msg")
            }
            _ui.value = _ui.value.copy(
                loading = false,
                config = configResult.getOrNull(),
                configError = configResult.exceptionOrNull()?.message,
                errorLog = errorLogResult.getOrNull().orEmpty(),
                errorLogError = errorLogResult.exceptionOrNull()?.message,
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { SystemHealthViewModel(haRepository) }
        }
    }
}
