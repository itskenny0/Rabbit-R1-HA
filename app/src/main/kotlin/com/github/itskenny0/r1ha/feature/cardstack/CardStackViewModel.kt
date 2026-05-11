package com.github.itskenny0.r1ha.feature.cardstack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.DebouncedCaller
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class CardStackUiState(
    val cards: List<EntityState> = emptyList(),
    val currentIndex: Int = 0,
    /** Optimistic percent overrides per entity, applied while the debounce is pending. */
    val optimisticPercents: Map<EntityId, Int> = emptyMap(),
) {
    val activeState: EntityState?
        get() = cards.getOrNull(currentIndex)?.let { state ->
            val override = optimisticPercents[state.id]
            if (override != null) state.copy(percent = override) else state
        }
}

class CardStackViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val wheelInput: WheelInput,
) : ViewModel() {

    private val _state = MutableStateFlow(CardStackUiState())
    val state: StateFlow<CardStackUiState> = _state

    private val debounced = DebouncedCaller<EntityId, Int>(
        scope = viewModelScope,
        debounceMillis = 400L,
    ) { entityId, pct ->
        haRepository.call(ServiceCall.setPercent(entityId, pct))
        // Clear optimistic override once the call is dispatched
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents - entityId
        )
    }

    init {
        observeFavorites()
        collectWheelEvents()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFavorites() {
        settings.settings
            .map { appSettings ->
                appSettings.favorites
                    .filter { id ->
                        runCatching { EntityId(id) }.isSuccess
                    }
                    .map { EntityId(it) }
                    .toSet()
            }
            .flatMapLatest { entityIds ->
                if (entityIds.isEmpty()) flowOf(emptyMap())
                else haRepository.observe(entityIds)
            }
            .combine(_state) { entityMap, currentState ->
                // Preserve order from favorites list; fall back to map insertion order
                val orderedCards = entityMap.values.sortedBy { it.id.value }
                val clampedIndex = currentState.currentIndex.coerceIn(0, (orderedCards.size - 1).coerceAtLeast(0))
                currentState.copy(cards = orderedCards, currentIndex = clampedIndex)
            }
            .onEach { newState -> _state.value = newState }
            .launchIn(viewModelScope)
    }

    private fun collectWheelEvents() {
        wheelInput.events
            .onEach { event -> onWheel(event) }
            .launchIn(viewModelScope)
    }

    private suspend fun onWheel(event: WheelEvent) {
        val appSettings = settings.settings.first()
        val wheel = appSettings.wheel
        val activeState = _state.value.activeState ?: return

        // Measure rate from recent events using the current timestamp
        val ratePerSec = 0.0 // Simplified — acceleration uses last burst; WheelInput has no rate tracker
        val step = WheelInput.effectiveStep(wheel.stepPercent, ratePerSec, wheel.acceleration)
        val sign = WheelInput.applyDirection(event.direction, wheel.invertDirection)
        val currentPct = _state.value.optimisticPercents[activeState.id] ?: activeState.percent ?: 0
        val newPct = (currentPct + sign * step).coerceIn(0, 100)

        // Apply optimistic update
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )

        // Submit debounced call
        viewModelScope.launch {
            debounced.submit(activeState.id, newPct)
        }
    }

    fun next() {
        val size = _state.value.cards.size
        if (size == 0) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex + 1).mod(size)
        )
    }

    fun previous() {
        val size = _state.value.cards.size
        if (size == 0) return
        _state.value = _state.value.copy(
            currentIndex = (_state.value.currentIndex - 1).mod(size)
        )
    }

    fun tapToggle() {
        val activeState = _state.value.activeState ?: return
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(activeState.id, activeState.isOn))
        }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: SettingsRepository,
            wheelInput: WheelInput,
        ) = viewModelFactory {
            initializer {
                CardStackViewModel(
                    haRepository = haRepository,
                    settings = settings,
                    wheelInput = wheelInput,
                )
            }
        }
    }
}
