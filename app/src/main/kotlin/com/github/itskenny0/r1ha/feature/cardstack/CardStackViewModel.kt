package com.github.itskenny0.r1ha.feature.cardstack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.DebouncedCaller
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.WheelSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CardStackUiState(
    /** Ordered by the user's favorites list (NOT by entity_id). The HA cache snapshot —
     *  what the server has confirmed. The UI should bind to [displayedCards] instead so
     *  optimistic wheel/tap updates are visible *immediately* rather than after the round-
     *  trip; this raw view is kept so the activeState getter and the optimistic-filter in
     *  `observeFavorites` can still see "what HA actually thinks" separately. */
    val cards: List<EntityState> = emptyList(),
    val currentIndex: Int = 0,
    /** Optimistic percent overrides per entity, applied while waiting for HA state_changed. */
    val optimisticPercents: Map<EntityId, Int> = emptyMap(),
    /**
     * Number of entity IDs in the user's favourites list, regardless of whether HA has
     * sent state for them yet. Distinguishes "no favourites set" (zero) from "favourites
     * set but waiting on HA" (non-zero with empty `cards`).
     */
    val favouritesCount: Int = 0,
    /**
     * Per-light transient wheel-mode override. Defaults to BRIGHTNESS for any light
     * the user hasn't toggled. Not persisted — the user re-picks each session, which
     * matches the "tap the readout to cycle" UX (a one-shot adjustment rather than a
     * permanent preference).
     */
    val lightWheelMode: Map<EntityId, com.github.itskenny0.r1ha.core.ha.LightWheelMode> = emptyMap(),
) {
    /**
     * Apply optimistic overrides on top of [cards] so the UI sees the user's intent
     * immediately — without this, the brightness bar only "updates" when HA echoes the
     * state_changed event back, which is the round-trip latency the user perceived as
     * sluggishness. The optimistic clears automatically once HA confirms (see
     * `observeFavorites`'s filter) — at that point the cached value matches the override
     * and the result of [applyOptimistic] is identical to the cached value, so the UI
     * doesn't bounce.
     */
    val displayedCards: List<EntityState>
        get() = cards.map { state -> state.applyOptimistic(optimisticPercents[state.id]) }

    val activeState: EntityState?
        get() = cards.getOrNull(currentIndex)?.applyOptimistic(
            optimisticPercents[cards.getOrNull(currentIndex)?.id],
        )

    /**
     * Convenience for the UI: the currently-displayed (post-optimistic) card. Same as
     * [activeState] but expressed as a `displayedCards[currentIndex]` lookup for symmetry.
     */
    val displayedActiveState: EntityState?
        get() = displayedCards.getOrNull(currentIndex)
}

/** Layer the optimistic override onto a cached state. */
private fun EntityState.applyOptimistic(override: Int?): EntityState {
    if (override == null) return this
    return if (supportsScalar) {
        // Scalar entity: optimistic just overrides the percent.
        copy(percent = override)
    } else {
        // Switch entity: encode desired ON in optimistic >= 1, OFF in 0. Flip isOn
        // immediately so the switch card snaps to the chosen position rather than
        // waiting for HA's state broadcast.
        copy(percent = override, isOn = override > 0)
    }
}

class CardStackViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val wheelInput: WheelInput,
) : ViewModel() {

    private val _state = MutableStateFlow(CardStackUiState())
    val state: StateFlow<CardStackUiState> = _state

    /** Snapshot of WheelSettings refreshed by the settings observer. We hold this in a
     *  separate field instead of calling settings.first() per wheel event — even though a
     *  hot Flow's first() is fast, doing it 50 times/sec inside the wheel collector lets
     *  events queue + drop in the SharedFlow buffer, manifesting as the readout jumping in
     *  irregular chunks rather than tracking the wheel. */
    @Volatile private var cachedWheel: WheelSettings = WheelSettings()

    /** Recent wheel-event timestamps; used to compute rate for acceleration. */
    private val wheelTimestamps = ArrayDeque<Long>()
    private val rateWindowMillis = 250L

    /** Latest entityOverrides snapshot — same caching pattern as cachedWheel so the
     *  debouncer doesn't have to suspend on settings.first() per fire. Updated by the
     *  init observer below. */
    @Volatile private var cachedOverrides: Map<String, com.github.itskenny0.r1ha.core.prefs.EntityOverride> = emptyMap()

    private val debounced = DebouncedCaller<EntityId, Int>(
        scope = viewModelScope,
        debounceMillis = 250L,
    ) { entityId, pct ->
        // Look up the entity's current state to pick the right service shape: scalar entities
        // get setPercent (turn_on with brightness_pct, set_percentage, set_cover_position,
        // volume_set), switch entities get the discrete setSwitch (turn_on/turn_off, open/
        // close_cover, media_play/media_pause). Reading the state at fire-time means the
        // wheel-up→ON and wheel-down→OFF intent stays accurate even if HA's state has shifted
        // during the 250 ms debounce.
        val entityState = _state.value.cards.firstOrNull { it.id == entityId }
        val call = when {
            entityState?.supportsScalar == false -> {
                R1Log.i("CardStack.debounced", "sending setSwitch($entityId, on=${pct > 0})")
                ServiceCall.setSwitch(entityId, on = pct > 0)
            }
            // Climate scalar — convert the wheel's 0..100 into the entity's temperature
            // range using minRaw/maxRaw. Falls through to setPercent if the range is
            // missing (which shouldn't happen because supportsScalar=true requires it,
            // but defensive code in case the cached state is stale).
            (entityState?.id?.domain == com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ||
                entityState?.id?.domain == com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER) &&
                entityState.minRaw != null && entityState.maxRaw != null -> {
                // Snap to the nearest 0.5° so the wheel's percent-step (~1.67% per detent
                // for a 30° span) doesn't accumulate floating-point drift; the user gets
                // reliable half-degree increments matching thermostat conventions.
                // water_heater shares the same set_temperature service signature.
                val raw = entityState.minRaw + (pct / 100.0) * (entityState.maxRaw - entityState.minRaw)
                val temp = Math.round(raw * 2.0) / 2.0
                R1Log.i("CardStack.debounced", "sending setTemperature($entityId, ${"%.1f".format(temp)})")
                ServiceCall.setTemperature(entityId, temp)
            }
            // Number / input_number scalar — same shape as climate but emits set_value
            // on the entity's own domain. Wheel's 0..100 maps onto min..max from attrs.
            entityState != null && entityState.minRaw != null && entityState.maxRaw != null &&
                (entityState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.NUMBER ||
                    entityState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.INPUT_NUMBER) -> {
                val raw = entityState.minRaw + (pct / 100.0) * (entityState.maxRaw - entityState.minRaw)
                // Snap to the entity's native `step` so a value like 42.7341 lands on a
                // clean multiple (42.7 if step=0.1, 43 if step=1, 45 if step=5). HA
                // would round anyway on receipt, but doing it ourselves keeps the
                // displayed value honest and the optimistic state coherent.
                val step = entityState.step?.takeIf { it > 0.0 }
                val snapped = if (step != null) Math.round(raw / step) * step else raw
                val clamped = snapped.coerceIn(entityState.minRaw, entityState.maxRaw)
                R1Log.i("CardStack.debounced", "sending setNumberValue($entityId, ${"%.3f".format(clamped)} step=$step)")
                ServiceCall.setNumberValue(entityId, clamped)
            }
            // Light wheel-mode dispatch — when the user has cycled into CT or HUE mode
            // for a light, the wheel's 0..100 is reinterpreted into kelvin or degrees
            // and sent on the appropriate service-call shape. Keeps brightness at its
            // current value so changing CT doesn't accidentally also change brightness.
            entityId.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT &&
                _state.value.lightWheelMode[entityId] == com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
                val minK = entityState?.minColorTempK ?: 2000
                val maxK = entityState?.maxColorTempK ?: 6500
                val k = (minK + (pct / 100.0) * (maxK - minK)).roundToInt().coerceIn(minK, maxK)
                // Carry over current brightness so the bulb stays at its existing level
                // while we tint it. If the bulb is currently off (percent=0/null) we omit
                // brightness — HA's set_color_temp doesn't turn the bulb on on its own,
                // but at least the call doesn't accidentally turn it OFF either.
                val carryBright = entityState?.percent?.takeIf { it > 0 }
                R1Log.i("CardStack.debounced", "sending setLightColorTemp($entityId, ${k}K, bright=$carryBright)")
                ServiceCall.setLightColorTemp(entityId, k, brightnessPct = carryBright)
            }
            entityId.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT &&
                _state.value.lightWheelMode[entityId] == com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
                val hue = (pct / 100.0) * 360.0
                val carryBright = entityState?.percent?.takeIf { it > 0 }
                R1Log.i("CardStack.debounced", "sending setLightHue($entityId, ${"%.0f".format(hue)}°, bright=$carryBright)")
                ServiceCall.setLightHue(entityId, hue, brightnessPct = carryBright)
            }
            else -> {
                // Standard scalar dispatch. If this is a light AND the user has set a
                // per-card colour-temperature override, fold that into the turn_on so
                // the bulb both brightens and shifts to the preferred CT in a single
                // service call. HA accepts color_temp_kelvin alongside brightness_pct
                // on lights that report `supported_color_modes` containing `color_temp`.
                val override = cachedOverrides[entityId.value]
                val ctK = override?.lightColorTempK
                if (entityId.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT &&
                    ctK != null && pct > 0) {
                    R1Log.i("CardStack.debounced", "sending light.turn_on($entityId, ${pct}% @ ${ctK}K)")
                    ServiceCall(
                        target = entityId,
                        service = "turn_on",
                        data = kotlinx.serialization.json.buildJsonObject {
                            put("brightness_pct", kotlinx.serialization.json.JsonPrimitive(pct))
                            put("color_temp_kelvin", kotlinx.serialization.json.JsonPrimitive(ctK))
                        },
                    )
                } else {
                    R1Log.i("CardStack.debounced", "sending setPercent($entityId, $pct)")
                    ServiceCall.setPercent(entityId, pct)
                }
            }
        }
        // haRepository.call() returns success immediately (the actual HA round-trip lives
        // inside the repo's own debouncer); failures arrive asynchronously via the
        // [callFailures] SharedFlow, which we observe in `init` and translate into an
        // optimistic rollback. On success we do NOTHING — the optimistic is cleared by
        // observeFavorites when HA echoes back the matching state_changed event. That
        // ordering is critical: clearing here would briefly show HA's *old* cached state
        // before the event arrives, which reads as a flicker.
        haRepository.call(call)
    }

    init {
        observeFavorites()
        // Keep a non-suspend snapshot of WheelSettings so onWheel() never has to hit the
        // settings Flow on the hot path.
        settings.settings
            .map { it.wheel }
            .distinctUntilChanged()
            .onEach { cachedWheel = it }
            .launchIn(viewModelScope)
        settings.settings
            .map { it.entityOverrides }
            .distinctUntilChanged()
            .onEach { cachedOverrides = it }
            .launchIn(viewModelScope)
        // Roll back the optimistic override whenever HA tells us a service call failed —
        // the UI bounces back to the last-known cached value so the user can see their
        // input didn't take, rather than the slider sitting stuck on their intent.
        haRepository.callFailures
            .onEach { id ->
                R1Log.w("CardStack.failure", "$id rejected by HA — reverting optimistic")
                _state.value = _state.value.copy(
                    optimisticPercents = _state.value.optimisticPercents - id,
                )
            }
            .launchIn(viewModelScope)
        // Wheel events are NOT collected here. They're collected by CardStackScreen only
        // while it is in composition, so spinning the wheel from any other screen does not
        // silently change the active card's brightness.
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFavorites() {
        // The favourites list is the source of truth for both membership and order; the
        // override map is the source of truth for display names. Combined so a rename
        // surfaces on the card-stack screen immediately, without needing to re-fetch HA.
        val sourceFlow = settings.settings
            .map { it.favorites to it.nameOverrides }
            .distinctUntilChanged()

        sourceFlow
            .flatMapLatest { (favouriteIds, overrides) ->
                val ids = favouriteIds.mapNotNull { runCatching { EntityId(it) }.getOrNull() }.toSet()
                if (ids.isEmpty()) {
                    flowOf(Triple(favouriteIds, overrides, emptyMap<EntityId, EntityState>()))
                } else {
                    haRepository.observe(ids).map { Triple(favouriteIds, overrides, it) }
                }
            }
            .onEach { (favouriteIds, overrides, entityMap) ->
                // Preserve user-chosen order; drop entries that aren't yet known to HA.
                // Apply the rename override at this layer so every UI surface (theme.Card,
                // SwitchCard, ActionCard, SensorCard) sees the renamed name without each
                // composable having to know about the override mechanism.
                val ordered = favouriteIds.mapNotNull { id ->
                    runCatching { EntityId(id) }.getOrNull()?.let { eid ->
                        entityMap[eid]?.let { state ->
                            overrides[state.id.value]?.let { renamed ->
                                state.copy(friendlyName = renamed)
                            } ?: state
                        }
                    }
                }
                R1Log.d("CardStack.observe", "favoriteIds=${favouriteIds.size} ordered=${ordered.size}")
                val cur = _state.value
                val clampedIndex = if (ordered.isEmpty()) 0
                    else cur.currentIndex.coerceIn(0, ordered.size - 1)
                // Trim optimistic entries in three cases:
                //   1) The server caught up — for SCALAR entities, that means cached percent
                //      matches the optimistic value. For SWITCH entities, the repo never
                //      produces a percent (only isOn), so we instead check whether the
                //      cached isOn matches our optimistic intent (override > 0). Without this
                //      switch-aware branch, an automation that flips the entity from outside
                //      the app leaves the optimistic stuck and the card keeps showing the
                //      old state until the user manually toggles it back.
                //   2) The entity is no longer in the favourites set at all (user un-favourited
                //      it before HA echoed back). Without (2) the override map slowly grows
                //      every time someone toggles a favourite off.
                //   3) The cache hasn't seen this entity yet — keep the optimistic so the UI
                //      doesn't bounce while waiting for the first state.
                val favoriteSet = ordered.map { it.id }.toSet()
                val newOptimistic = cur.optimisticPercents.filter { (id, optPct) ->
                    if (id !in favoriteSet) return@filter false
                    val cached = entityMap[id] ?: return@filter true
                    if (cached.supportsScalar) {
                        val cachedPct = cached.percent
                        cachedPct == null || cachedPct != optPct
                    } else {
                        cached.isOn != (optPct > 0)
                    }
                }
                _state.value = cur.copy(
                    cards = ordered,
                    currentIndex = clampedIndex,
                    optimisticPercents = newOptimistic,
                    favouritesCount = favouriteIds.size,
                )
            }
            .launchIn(viewModelScope)
    }

    /** Called from CardStackScreen when a wheel event arrives. Synchronous on the hot path —
     *  reads only cached state — so that 50 Hz wheel input doesn't back up in the SharedFlow
     *  buffer. The actual HA call is dispatched via the debouncer in its own coroutine. */
    fun onWheel(event: WheelEvent) {
        val wheel = cachedWheel
        val activeState = _state.value.activeState ?: return
        // Ignore wheel on unavailable entities: spinning would create a runaway optimistic
        // override that never reconciles because HA never responds with a state change.
        if (!activeState.isAvailable) return
        // Action-only entities (scenes, scripts, buttons) are tap-to-fire. Spinning the
        // wheel on top of an ActionCard shouldn't accidentally fire the trigger or paint
        // a phantom percent — wheels are deliberately a no-op there. Same story for
        // sensors / binary_sensors which are read-only.
        if (activeState.id.domain.isAction || activeState.id.domain.isSensor) return

        val sign = WheelInput.applyDirection(event.direction, wheel.invertDirection)

        // ── Switch (on/off only) entities: wheel sets absolute state ──────────────────
        if (!activeState.supportsScalar) {
            val newPct = if (sign > 0) 100 else 0
            _state.value = _state.value.copy(
                optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
            )
            viewModelScope.launch { debounced.submit(activeState.id, newPct) }
            return
        }

        // ── Scalar entities: wheel adjusts percent with optional acceleration ─────────
        // Sliding-window rate computation: count events in the last [rateWindowMillis] ms,
        // multiply by (1000 / window) to scale to events/sec.
        val now = event.timestampMillis
        wheelTimestamps.addLast(now)
        while (wheelTimestamps.isNotEmpty() && now - wheelTimestamps.first() > rateWindowMillis) {
            wheelTimestamps.removeFirst()
        }
        val ratePerSec = wheelTimestamps.size * (1000.0 / rateWindowMillis)

        // For climate the user wants 0.5° increments and "half the speed of lights" —
        // i.e. each detent should advance the temperature by a deliberate small amount,
        // not a fixed % of brightness. Compute the percent-equivalent of 0.5° from the
        // entity's min/max range. With a typical 5..35°C range (30° span), 0.5° ≈ 1.67%
        // which rounds to 2% — and combined with the snap-to-0.5° at service-call time
        // the wheel feels exactly like a thermostat dial.
        val step = if ((activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ||
                activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER) &&
            activeState.minRaw != null && activeState.maxRaw != null) {
            val range = activeState.maxRaw - activeState.minRaw
            if (range > 0) {
                ((50.0 / range).roundToInt()).coerceAtLeast(1)
            } else 1
        } else {
            WheelInput.effectiveStep(
                base = wheel.stepPercent,
                ratePerSec = ratePerSec,
                accelerate = wheel.acceleration,
                curve = wheel.accelerationCurve,
            )
        }
        val currentPct = _state.value.optimisticPercents[activeState.id] ?: activeState.percent ?: 0
        val newPct = (currentPct + sign * step).coerceIn(0, 100)
        R1Log.d("CardStack.onWheel", "dir=${event.direction} step=$step (rate=$ratePerSec) -> $currentPct→$newPct")

        // Apply optimistic update synchronously
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )

        // Submit debounced call (trails by debounceMillis). Suspend-free hot path: we launch
        // the submit so onWheel can return immediately and the next event isn't blocked.
        viewModelScope.launch { debounced.submit(activeState.id, newPct) }
    }

    /** Sync the VM's active-card index with the pager's settled page. The wheel/tap handlers
     *  read activeState off of this index, so it has to track whatever page the user has just
     *  paged to. */
    fun setCurrentIndex(index: Int) {
        val size = _state.value.cards.size
        if (size == 0) return
        _state.value = _state.value.copy(currentIndex = index.coerceIn(0, size - 1))
    }

    /**
     * Fire the per-card long-press action. [target] is an HA entity_id (e.g. `scene.x`,
     * `script.y`, `switch.z`); we parse it and dispatch the same tap-action the entity's
     * own card would, which means scenes/scripts fire turn_on, buttons fire press,
     * switches toggle, etc. Invalid or unsupported targets toast and noop.
     */
    /**
     * Cycle the light wheel-mode for [entityId] through its available modes (derived
     * from supportedColorModes). Wraps at the end; one-mode entities (non-coloured
     * dimmable bulbs) become a no-op. Called from the BigReadout-suffix tap on light
     * cards.
     */
    /**
     * Cycle the light's effect through its effect_list. None → first effect → second →
     * … → None (so the user can wrap back to no-effect by stepping past the end). Sends
     * `light.turn_on` with the new effect; HA accepts "None" as the no-effect sentinel.
     */
    fun cycleLightEffect(entityId: EntityId) {
        val entity = _state.value.cards.firstOrNull { it.id == entityId } ?: return
        if (entity.id.domain != Domain.LIGHT || entity.effectList.isEmpty()) return
        // Sequence with null at both ends so cycling wraps cleanly: null → first → ... → last → null.
        val sequence: List<String?> = listOf(null) + entity.effectList
        val currentIdx = sequence.indexOf(entity.effect).let { if (it == -1) 0 else it }
        val next = sequence[(currentIdx + 1) % sequence.size]
        R1Log.i("CardStack.cycleEffect", "$entityId: ${entity.effect ?: "none"} → ${next ?: "none"}")
        viewModelScope.launch {
            haRepository.call(ServiceCall.setLightEffect(entityId, next))
        }
    }

    /**
     * Set the wheel mode for a light card to a specific value (no cycling). Used by the
     * segmented mode buttons on the card so the user can jump directly to BRIGHTNESS /
     * WHITE / COLOUR rather than having to cycle through other modes to reach the one
     * they want. Same optimistic-seeding logic as [cycleLightWheelMode] — the wheel
     * percent gets seeded to whatever the bulb is currently showing in the new mode so
     * the readout doesn't bounce when the user changes modes.
     */
    fun setLightWheelMode(entityId: EntityId, mode: com.github.itskenny0.r1ha.core.ha.LightWheelMode) {
        val entity = _state.value.cards.firstOrNull { it.id == entityId } ?: return
        if (entity.id.domain != Domain.LIGHT) return
        val available = com.github.itskenny0.r1ha.core.ha.LightWheelMode.availableFor(entity.supportedColorModes)
        if (mode !in available) return
        val current = _state.value.lightWheelMode[entityId] ?: com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS
        if (current == mode) return
        // Seed the optimistic percent so the wheel starts at the bulb's current value
        // in the new mode — same shape as cycleLightWheelMode().
        val seedPercent = when (mode) {
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> entity.percent ?: 0
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
                val minK = entity.minColorTempK ?: 2000
                val maxK = entity.maxColorTempK ?: 6500
                val k = entity.colorTempK ?: ((minK + maxK) / 2)
                if (maxK > minK) {
                    ((k - minK).toDouble() / (maxK - minK) * 100.0).roundToInt().coerceIn(0, 100)
                } else 50
            }
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
                val h = entity.hue ?: 0.0
                (h / 360.0 * 100.0).roundToInt().coerceIn(0, 100)
            }
        }
        _state.value = _state.value.copy(
            lightWheelMode = _state.value.lightWheelMode + (entityId to mode),
            optimisticPercents = _state.value.optimisticPercents + (entityId to seedPercent),
        )
    }

    /**
     * Apply a specific effect to a light, picked from its [EntityState.effectList]. Null
     * clears the effect (HA accepts the literal string "None" for "no effect"). Used
     * by the effect picker sheet so the user can jump directly to a named effect rather
     * than tapping through the cycle. Also force the wheel back to BRIGHTNESS — once an
     * effect is running it owns the bulb's colour, so HUE / WHITE wheel modes can't do
     * anything useful and only brightness remains as a meaningful axis to nudge. When
     * the user clears the effect (effect = null) the mode is left alone so they don't
     * lose a HUE/WHITE selection they may want to return to.
     */
    fun setLightEffect(entityId: EntityId, effect: String?) {
        val entity = _state.value.cards.firstOrNull { it.id == entityId } ?: return
        if (entity.id.domain != Domain.LIGHT) return
        R1Log.i("CardStack.setEffect", "$entityId: ${entity.effect ?: "none"} → ${effect ?: "none"}")
        if (!effect.isNullOrBlank()) {
            val brightnessMode = com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS
            val cur = _state.value.lightWheelMode[entityId]
            if (cur != null && cur != brightnessMode) {
                setLightWheelMode(entityId, brightnessMode)
            }
        }
        viewModelScope.launch {
            haRepository.call(ServiceCall.setLightEffect(entityId, effect))
        }
    }

    /**
     * Fire a media-player transport / volume action — play/pause, next, prev, vol+, vol-,
     * mute. Surfaced by the media_player card's [MediaControlsRow]. Each tap is a
     * one-shot service call with no payload; the volume wheel is still the primary way
     * to set absolute volume, but discrete +/- taps are easier for small adjustments.
     */
    fun mediaTransport(entityId: EntityId, action: com.github.itskenny0.r1ha.core.ha.MediaTransport) {
        val entity = _state.value.cards.firstOrNull { it.id == entityId } ?: return
        if (entity.id.domain != Domain.MEDIA_PLAYER) return
        R1Log.i("CardStack.media", "$entityId $action")
        viewModelScope.launch {
            haRepository.call(
                com.github.itskenny0.r1ha.core.ha.ServiceCall.mediaTransport(entityId, action),
            )
        }
    }

    fun cycleLightWheelMode(entityId: EntityId) {
        val entity = _state.value.cards.firstOrNull { it.id == entityId } ?: return
        if (entity.id.domain != Domain.LIGHT) return
        val available = com.github.itskenny0.r1ha.core.ha.LightWheelMode.availableFor(entity.supportedColorModes)
        if (available.size <= 1) return
        val current = _state.value.lightWheelMode[entityId] ?: com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS
        val nextIdx = (available.indexOf(current) + 1) % available.size
        val next = available[nextIdx]

        // Seed the optimistic percent so the wheel starts at the bulb's current value
        // in the new mode — switching from BRIGHTNESS 60% into CT mode shouldn't land
        // the wheel at 60% of the CT range; it should land at whatever CT the bulb is
        // currently showing.
        val seedPercent = when (next) {
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> entity.percent ?: 0
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
                val minK = entity.minColorTempK ?: 2000
                val maxK = entity.maxColorTempK ?: 6500
                val k = entity.colorTempK ?: ((minK + maxK) / 2)
                if (maxK > minK) {
                    ((k - minK).toDouble() / (maxK - minK) * 100.0).roundToInt().coerceIn(0, 100)
                } else 50
            }
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
                val h = entity.hue ?: 0.0
                (h / 360.0 * 100.0).roundToInt().coerceIn(0, 100)
            }
        }

        _state.value = _state.value.copy(
            lightWheelMode = _state.value.lightWheelMode + (entityId to next),
            optimisticPercents = _state.value.optimisticPercents + (entityId to seedPercent),
        )
        R1Log.i("CardStack.cycleMode", "$entityId: $current → $next (seed=$seedPercent%)")
    }

    /** Persist a customize-dialog edit straight from the card-stack. Same write path as
     *  the favourites picker's saveCustomize — name override + entity override map.
     *  Empty new name removes the name override; defaults-only entity override removes
     *  the entry from the entityOverrides map. */
    fun saveCustomize(
        entityId: String,
        newName: String,
        newOverride: com.github.itskenny0.r1ha.core.prefs.EntityOverride,
    ) {
        viewModelScope.launch {
            settings.update { cur ->
                val trimmed = newName.trim()
                val nextNames = cur.nameOverrides.toMutableMap()
                if (trimmed.isBlank()) nextNames.remove(entityId) else nextNames[entityId] = trimmed
                val nextOverrides = cur.entityOverrides.toMutableMap()
                if (newOverride == com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE) {
                    nextOverrides.remove(entityId)
                } else {
                    nextOverrides[entityId] = newOverride
                }
                cur.copy(nameOverrides = nextNames, entityOverrides = nextOverrides)
            }
        }
    }

    fun fireLongPress(target: String) {
        val targetId = runCatching { EntityId(target) }.getOrNull()
        if (targetId == null) {
            R1Log.w("CardStack.longPress", "ignoring invalid target '$target'")
            com.github.itskenny0.r1ha.core.util.Toaster.show(
                "Long-press target '$target' isn't a recognised entity",
            )
            return
        }
        viewModelScope.launch {
            R1Log.i("CardStack.longPress", "firing $targetId")
            // For toggleable domains we tap-toggle relative to the current cached state;
            // for action-only domains (scene/script/button) tapAction always fires the
            // trigger regardless of isOn. The cached state may not exist (the user might
            // point at an entity they haven't favourited) — default to isOn=false so the
            // toggle dispatches a "turn_on" / "open_cover" / etc. which is the natural
            // intent of "activate this from a long press".
            val cachedIsOn = _state.value.cards.firstOrNull { it.id == targetId }?.isOn ?: false
            haRepository.call(ServiceCall.tapAction(targetId, cachedIsOn))
        }
    }

    fun tapToggle() {
        val activeState = _state.value.activeState ?: return
        if (!activeState.isAvailable) return  // can't toggle an unreachable entity
        viewModelScope.launch {
            // Cover-mid-travel special case: if HA reports the cover as actively moving
            // (state="opening"/"closing"), a tap should STOP it rather than flip its
            // open/close intent. That's the natural mental model — tap a moving blind to
            // halt it where it is, tap a stationary one to flip its direction. Falls back
            // to the standard toggle for every other state and every other domain.
            val isCoverMoving = activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.COVER &&
                (activeState.rawState == "opening" || activeState.rawState == "closing")
            val call = if (isCoverMoving) {
                R1Log.i("CardStack.tap", "stop ${activeState.id} (state=${activeState.rawState})")
                ServiceCall(
                    target = activeState.id,
                    service = "stop_cover",
                    data = kotlinx.serialization.json.JsonObject(emptyMap()),
                )
            } else {
                R1Log.i("CardStack.tap", "toggle ${activeState.id} isOn=${activeState.isOn}")
                ServiceCall.tapAction(activeState.id, activeState.isOn)
            }
            haRepository.call(call)
        }
    }

    /**
     * Set the active switch-card entity to an explicit [on] state — wired to the ON/OFF
     * labels on [SwitchCard]. Re-uses the same optimistic + debouncer pipeline as the wheel
     * so a rapid tap-ON tap-OFF still resolves to the last intent.
     */
    fun setSwitchOn(on: Boolean) {
        val activeState = _state.value.activeState ?: return
        if (!activeState.isAvailable) return
        val newPct = if (on) 100 else 0
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )
        viewModelScope.launch { debounced.submit(activeState.id, newPct) }
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
