package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Read a JSON attribute as a plain String, regardless of whether HA encoded it as a JSON string or number. */
private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.content
/** Read a JSON attribute as Int. Works for both JsonPrimitive(123) and JsonPrimitive("123"). */
private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()
/** Read a JSON attribute as Double. Works for both JsonPrimitive(0.42) and JsonPrimitive("0.42"). */
private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

class DefaultHaRepository(
    private val ws: HaWebSocketClient,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    /**
     * Optional refresher; production wires in [TokenRefresher], tests can pass null to skip the
     * network calls entirely and reuse whatever access token the test stubbed into [tokens].
     */
    private val refresher: TokenRefresher? = null,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Optional disk persister for the entity cache — seeds [cache] on start
     * with the last-seen snapshot so the card stack paints immediately at
     * cold-start, before the WS even connects. Null in tests so they don't
     * accidentally read a developer's snapshot from /tmp.
     */
    private val persister: EntityStateCachePersister? = null,
) : HaRepository {

    override val connection: StateFlow<ConnectionState> = ws.state

    /** Failures broadcast to the ViewModel so it can roll back optimistic overrides. */
    private val _callFailures = MutableSharedFlow<EntityId>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val callFailures: SharedFlow<EntityId> = _callFailures.asSharedFlow()

    private val cache = MutableStateFlow<Map<EntityId, EntityState>>(emptyMap())
    private val pendingCalls = ConcurrentHashMap<Int, CompletableDeferred<Result<Unit>>>()
    private var supervisorJob: Job? = null
    private var subscriptionId: Int? = null
    /** Tracks the currently-scheduled reconnect-backoff job so [reconnectNow] can cancel it. */
    @Volatile private var pendingReconnect: Job? = null

    /** Wall-clock target for the next scheduled reconnect. UI reads this for the
     *  countdown text. Cleared when we connect or when reconnectNow() short-circuits
     *  the backoff. */
    private val _reconnectAt = MutableStateFlow<Long?>(null)
    override val reconnectNextAttemptAtMillis: StateFlow<Long?> = _reconnectAt.asStateFlow()

    /** Tracks consecutive reconnect attempts so BackoffPolicy actually backs off. */
    @Volatile private var reconnectAttempt: Int = 0

    /**
     * Tracks AuthLost-driven refresh attempts so we don't tight-loop if a misconfigured HA
     * keeps issuing access tokens that fail auth. Reset on Connected.
     */
    @Volatile private var authLostRefreshAttempt: Int = 0

    // Key the per-call debouncer by (target, service) rather than just (target).
    // Without the service segment, rapid taps of distinct media-transport buttons
    // (PLAY → NEXT → VOL+) all collapsed onto the same EntityId-only key and
    // cancelled each other — only the last submission inside the 120 ms window
    // would actually fire. Different services on the same entity now go through
    // separate pending slots so each one ships; identical-service calls still
    // coalesce, which is the wanted behaviour for scalar wheel/touch streams (the
    // last brightness value during a sustained spin is the only one HA needs).
    private val debouncer = DebouncedCaller<Pair<EntityId, String>, ServiceCall>(scope, debounceMillis = 120) { _, call ->
        val id = ws.nextRequestId()
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingCalls[id] = deferred
        ws.send(HaOutbound.CallService(id, call.haDomain, call.service, call.target.value, call.data))
        // Wait for HA's Result with a hard ceiling. Without the timeout a slow/dead HA leaves
        // the deferred in `pendingCalls` forever; without the await we lose visibility into
        // whether the command actually shipped. CALL_TIMEOUT_MS is generous enough that even
        // a busy HA on a flaky link finishes inside it; if it doesn't, the user wants to know.
        val outcome = try {
            withTimeout(CALL_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            // Drain the pending entry so we don't leak the deferred if a late Result eventually
            // arrives — the .remove() races with the inbound listener but ConcurrentHashMap
            // guarantees one of them wins cleanly.
            pendingCalls.remove(id)
            Result.failure(IllegalStateException("Timed out after ${CALL_TIMEOUT_MS / 1000}s"))
        }
        outcome.onFailure { t ->
            // R1Log gets the full picture (entity_id + service + message); the toast
            // is short enough to render legibly on the R1's 240×320 display. HA's
            // error strings can be paragraph-length ("Failed to call service light/turn_on:
            // Unable to find referenced entities…") and Android's Toast widget hard-
            // truncates anything past ~2 short lines, so a multi-line message gets cut
            // mid-sentence. We trim to the first ~28 chars of the underlying message,
            // surface only the entity's objectId, and use LENGTH_LONG so the user has
            // enough time to read it.
            R1Log.w("HaRepo.call", "${call.target.value}/${call.service} failed: ${t.message}")
            val rawMsg = t.message ?: "unknown error"
            val firstLine = rawMsg.lineSequence().firstOrNull().orEmpty()
            val shortMsg = if (firstLine.length > 28) firstLine.take(25) + "…" else firstLine
            // Surface full context in the expandable body so the user can tap the
            // toast to read the entire error (HA's "Validation error: Entity X
            // doesn't support service Y" runs well past the inline preview).
            // entity_id + service line gives a copy-paste handle for diagnosing
            // missing features (the most common cause of validation errors on
            // media_player integrations).
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "${call.target.objectId}: $shortMsg",
                fullText = buildString {
                    append(call.target.value).append('\n')
                    append(call.service).append(" failed\n\n")
                    append(rawMsg)
                },
            )
            // Tell the ViewModel so it can roll back the optimistic override — the slider
            // bounces back to HA's last-known value instead of sitting stuck on the user's
            // intent. tryEmit is fine: the buffer is bounded with DROP_OLDEST.
            _callFailures.tryEmit(call.target)
        }
    }

    override suspend fun start() {
        if (supervisorJob != null) return
        // Seed the in-memory cache from disk BEFORE we start the WS — IF the
        // user opted into disk persistence via Settings → Dev menu →
        // 'persistCacheToDisk'. Off by default while the rehydrate path is
        // being hardened (the rehydrated entities have null `raw` and null
        // `attributesJson` which one user's session caught in an
        // unguarded read path). Opt-in users get the cold-start speedup;
        // everyone else gets the safe behaviour.
        persister?.let { p ->
            val current = settings.settings.first()
            if (!current.advanced.persistCacheToDisk) {
                R1Log.i("HaRepo", "persistCacheToDisk=false; skipping disk-cache wiring")
                return@let
            }
            val restored = withContext(Dispatchers.IO) { p.load() }
            if (restored.isNotEmpty()) {
                cache.value = restored
                R1Log.i("HaRepo", "seeded cache from disk: ${restored.size} entities")
            }
            // Bind the persister to start collecting markDirty ticks. The
            // bind() call kicks off the debounce loop on [scope].
            p.bind()
            // Mirror every cache change into the persister so the snapshot
            // stays current. Debouncing happens inside markDirty's flow.
            cache.onEach { p.markDirty(it) }.launchIn(scope)
        }
        supervisorJob = scope.launch {
            ws.inbound.onEach { msg ->
                when (msg) {
                    is HaInbound.Result -> pendingCalls.remove(msg.id)?.complete(
                        if (msg.success) Result.success(Unit)
                        else Result.failure(IllegalStateException(msg.error?.message ?: "ha_error"))
                    )
                    is HaInbound.Event -> applyEvent(msg)
                    else -> Unit
                }
            }.launchIn(this)

            ws.state.onEach { st ->
                when (st) {
                    is ConnectionState.Connected -> {
                        reconnectAttempt = 0
                        authLostRefreshAttempt = 0
                        // Connected — there's nothing scheduled, so the UI countdown should
                        // stop. The pendingReconnect job, if any, has already fired and
                        // self-cleared this; this assignment is the belt-and-braces case
                        // where we landed in Connected via reconnectNow() or a manual
                        // start() while a backoff was pending.
                        _reconnectAt.value = null
                        resubscribe()
                        // Don't block the state observer on the REST seed (can take a few
                        // seconds with retries) — if a Disconnect happens mid-seed, the
                        // observer needs to be free to react to it, otherwise the conflated
                        // StateFlow would collapse a brief Connected → Disconnected → Connected
                        // bounce into a single observed Connected.
                        scope.launch { seedCacheFromHa() }
                    }
                    is ConnectionState.Disconnected -> {
                        // The WS client always reports st.attempt=0 (it has no notion of
                        // consecutive failures); we track the run here.
                        val attempt = reconnectAttempt
                        reconnectAttempt = (attempt + 1).coerceAtMost(20)
                        // Fail any in-flight service-call deferreds whose Result will never
                        // arrive — without this they leak into pendingCalls until the process
                        // dies and any awaiter would hang indefinitely.
                        if (pendingCalls.isNotEmpty()) {
                            pendingCalls.values.forEach {
                                it.complete(Result.failure(IllegalStateException("WS disconnected mid-call")))
                            }
                            pendingCalls.clear()
                        }
                        reconnectLater(attempt)
                    }
                    is ConnectionState.AuthLost -> {
                        // Access token was rejected — most often because the 30-minute lifetime
                        // expired. Try one refresh; if it succeeds, reconnect. If the refresh
                        // itself fails (revoked refresh token, server unreachable, etc.) we stay
                        // in AuthLost and the user has to manually sign out & reconnect.
                        // Bounded to MAX_AUTHLOST_RETRIES to avoid tight-looping if HA keeps
                        // issuing access tokens that fail auth (rare misconfiguration).
                        // Also drain pendingCalls — the WS was just closed by AuthInvalid so
                        // any outstanding Result deferreds won't ever complete naturally.
                        if (pendingCalls.isNotEmpty()) {
                            pendingCalls.values.forEach {
                                it.complete(Result.failure(IllegalStateException("WS auth lost")))
                            }
                            pendingCalls.clear()
                        }
                        val attempt = authLostRefreshAttempt
                        if (attempt >= MAX_AUTHLOST_RETRIES) {
                            R1Log.w("HaRepo.authLost", "max refresh attempts ($attempt) reached; staying AuthLost")
                            return@onEach
                        }
                        authLostRefreshAttempt = attempt + 1
                        R1Log.w("HaRepo.authLost", "reason=${st.reason}; attempting token refresh (try ${attempt + 1})")
                        scope.launch {
                            // Small backoff so a misbehaving HA doesn't get hammered.
                            delay(backoff.delayForAttempt(attempt))
                            if (refresher?.forceRefresh() == true) {
                                R1Log.i("HaRepo.authLost", "refresh succeeded; reconnecting")
                                connectFromSettings()
                            } else {
                                R1Log.w("HaRepo.authLost", "refresh failed; staying AuthLost")
                            }
                        }
                    }
                    else -> Unit
                }
            }.launchIn(this)

            // Re-subscribe + reseed the cache whenever the user's favourites change. Without
            // this the WS only receives subscribe_trigger for the initial favourites list
            // (taken at WS Connected) and never sees state_changed events for anything the
            // user adds later — so newly-added cards would sit at 0% until manually toggled
            // from elsewhere.
            settings.settings
                .map { it.favorites }
                .distinctUntilChanged()
                .onEach {
                    R1Log.i("HaRepo.favsChange", "favorites changed to ${it.size} entries")
                    if (ws.state.value is ConnectionState.Connected) {
                        resubscribe()
                        scope.launch { seedCacheFromHa() }
                    }
                }
                .launchIn(this)

            // Observe the server URL; connect when it appears and disconnect when it goes
            // away. We deliberately do NOT force-reconnect on URL changes while a connection
            // is in flight, because the only legal way to change URLs in this app is via the
            // sign-out flow (which sets URL to null first, triggering the disconnect branch).
            // That also lets tests that pre-wire a WS connection coexist with start() without
            // having their connection torn down.
            settings.settings
                .map { it.server?.url }
                .distinctUntilChanged()
                .onEach { url ->
                    R1Log.i("HaRepo.serverChange", "server URL now $url; ws.state=${ws.state.value::class.simpleName}")
                    // Reset the consecutive-failure counter on any URL transition so a sign-out
                    // followed by a sign-in starts the backoff schedule fresh instead of
                    // inheriting accumulated failures from the previous server.
                    reconnectAttempt = 0
                    authLostRefreshAttempt = 0
                    if (url == null) {
                        // Drop any cached entity states from the previous server so the next
                        // sign-in starts fresh — otherwise stale data from server A could be
                        // briefly visible on cards when the user signs into server B with the
                        // same entity IDs.
                        cache.update { emptyMap() }
                        subscriptionId = null
                        // Fail any outstanding service-call awaiters; their WS is going away.
                        pendingCalls.values.forEach {
                            it.complete(Result.failure(IllegalStateException("Signed out")))
                        }
                        pendingCalls.clear()
                        ws.disconnect()
                        return@onEach
                    }
                    val st = ws.state.value
                    if (st is ConnectionState.Idle || st is ConnectionState.Disconnected) {
                        connectFromSettings()
                    }
                }
                .launchIn(this)
        }
    }

    override suspend fun stop() {
        supervisorJob?.cancel(); supervisorJob = null
        ws.disconnect()
    }

    private suspend fun connectFromSettings() {
        // Proactively refresh the access token if it's within ~60s of expiry. Cheap when the
        // token has time left (just an in-memory check), and avoids the AuthLost → refresh →
        // reconnect round-trip on the common "user opens app after >30min" case.
        refresher?.ensureFresh()
        val s = settings.settings.first()
        val server = s.server ?: return
        val t = tokens.load()
        if (t == null) {
            // Server is configured but we have no usable tokens — most often the Keystore key
            // got wiped (factory reset of secure storage), leaving encrypted tokens that can no
            // longer be decrypted. Without this signal the UI would sit on "Idle" forever; tell
            // the user explicitly to re-auth from Settings.
            R1Log.w("HaRepo.connect", "tokens.load() returned null even though server is set; user needs to re-auth")
            com.github.itskenny0.r1ha.core.util.Toaster.error(
                "Authentication tokens missing — open Settings → Sign out & reconnect",
            )
            return
        }
        val base = server.url.trimEnd('/')
        val wsUrl = when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://")  -> base.replaceFirst("http://", "ws://")
            else -> base
        } + "/api/websocket"
        ws.connect(wsUrl, t.accessToken)
    }

    private fun reconnectLater(attempt: Int) {
        // Track this job so reconnectNow() can cancel it and fire immediately. Cancel any
        // previously-pending reconnect first so two overlapping backoffs don't both fire
        // (would cause an immediate-after-delay double-connect from rapid bouncing).
        pendingReconnect?.cancel()
        val delayMs = backoff.delayForAttempt(attempt)
        _reconnectAt.value = System.currentTimeMillis() + delayMs
        pendingReconnect = scope.launch {
            delay(delayMs)
            _reconnectAt.value = null
            connectFromSettings()
        }
    }

    override fun reconnectNow() {
        val current = ws.state.value
        // Only honour the request when there's nothing useful in flight already — re-entering
        // a Connecting state would just thrash the WS client.
        if (current is ConnectionState.Connecting ||
            current is ConnectionState.Authenticating ||
            current is ConnectionState.Connected
        ) {
            R1Log.i("HaRepo.reconnectNow", "ignored (state=${current::class.simpleName})")
            return
        }
        pendingReconnect?.cancel()
        pendingReconnect = null
        // Clear the countdown target — we're firing now, not waiting. Without this the UI
        // would keep showing "RECONNECTING IN Xs…" briefly until Connected updates the
        // surrounding state, which looks broken when the user just tapped retry.
        _reconnectAt.value = null
        // Reset the consecutive-failure counter so the *next* backoff (if this attempt also
        // fails) starts from scratch — the user has signalled they want a fresh start.
        reconnectAttempt = 0
        R1Log.i("HaRepo.reconnectNow", "forcing immediate reconnect (was $current)")
        scope.launch { connectFromSettings() }
    }

    private fun applyEvent(ev: HaInbound.Event) {
        val raw = ev.event.variables.trigger.toState
        val idStr = raw.entityId ?: ev.event.variables.trigger.entityId
        val prefix = idStr.substringBefore('.', missingDelimiterValue = "")
        if (!Domain.isSupportedPrefix(prefix)) return
        val id = EntityId(idStr)
        // State-string → isOn mapping, branched by domain. Each domain has its own state
        // vocabulary in HA: lights/switches/input_boolean/automation/humidifier use
        // "on"/"off", media_players use "playing"/"paused"/"idle", covers use "open"/
        // "closed"/"opening"/"closing", locks use "locked"/"unlocked", thermostats use
        // the HVAC mode itself ("off"/"heat"/"cool"/"auto"/"dry"/"fan_only"). `isOn=true`
        // reads as "the affordance is engaged" — light on, switch on, cover open, lock
        // UNLOCKED (so the toggle reads intuitively: tap to lock when unlocked), thermostat
        // running.
        val isOn = when (id.domain) {
            Domain.LIGHT, Domain.FAN, Domain.SWITCH, Domain.INPUT_BOOLEAN,
            Domain.AUTOMATION, Domain.HUMIDIFIER -> raw.state.equals("on", ignoreCase = true)
            Domain.COVER, Domain.VALVE -> raw.state.equals("open", ignoreCase = true)
            Domain.MEDIA_PLAYER -> raw.state.equals("playing", ignoreCase = true)
            Domain.LOCK -> raw.state.equals("unlocked", ignoreCase = true)
            Domain.CLIMATE, Domain.WATER_HEATER -> !raw.state.equals("off", ignoreCase = true) &&
                raw.state != "unavailable" && raw.state != "unknown"
            // Scripts have an "on" state while they're executing. Scene/button never get
            // a meaningful on state — their state attribute is a last-fired timestamp.
            Domain.SCRIPT -> raw.state.equals("on", ignoreCase = true)
            Domain.SCENE, Domain.BUTTON, Domain.INPUT_BUTTON -> false
            // binary_sensor uses "on"/"off" by HA convention — "on" means the triggered
            // state (door open, motion detected, leak found). Plain sensor entities have
            // numeric/string readings and don't have a meaningful on/off mapping.
            Domain.BINARY_SENSOR -> raw.state.equals("on", ignoreCase = true)
            Domain.SENSOR -> false
            // number / input_number entities — state is the numeric value as a string.
            // "Non-zero" is the closest thing to "on" but it isn't very meaningful here;
            // the wheel just drives the value. Treat as false so tap-toggle doesn't try
            // to flip a slider to its zero/non-zero positions.
            Domain.NUMBER, Domain.INPUT_NUMBER -> false
            // Vacuum: any active state (cleaning, returning) reads as "on".
            Domain.VACUUM -> raw.state.equals("cleaning", ignoreCase = true) ||
                raw.state.equals("returning", ignoreCase = true) ||
                raw.state.equals("on", ignoreCase = true)
            // Select / input_select have no on/off — they're settable enums. Pin
            // isOn to false so tap-toggle doesn't try to flip them; the dedicated
            // picker overlay is the only way to change the option.
            Domain.SELECT, Domain.INPUT_SELECT -> false
        }
        val available = raw.state != "unavailable" && raw.state != "unknown"
        val pct = computePercentWithState(id.domain, raw.attributes, raw.state)
        val rawNum = computeRaw(id.domain, raw.attributes)
            ?: if (id.domain == Domain.NUMBER || id.domain == Domain.INPUT_NUMBER) raw.state.toDoubleOrNull() else null
        val newState = EntityState(
            id = id,
            friendlyName = raw.attributes["friendly_name"].asString() ?: id.objectId,
            area = raw.attributes["area_id"].asString(),
            isOn = isOn,
            percent = if (available) pct else null,
            raw = rawNum,
            lastChanged = runCatching { Instant.parse(raw.lastChanged ?: "") }.getOrDefault(Instant.now()),
            isAvailable = available,
            supportsScalar = supportsScalar(id.domain, raw.attributes),
            rawState = raw.state,
            // For climate, HA puts the temperature unit on `temperature_unit` rather than
            // `unit_of_measurement` (which it doesn't expose at all). Surface it through
            // the same `unit` field so the card display layer doesn't need to know.
            unit = raw.attributes["unit_of_measurement"].asString()
                ?: raw.attributes["temperature_unit"].asString(),
            deviceClass = raw.attributes["device_class"].asString(),
            // Range for any scalar with a custom span — climate (min_temp), humidifier
            // (min_humidity), number/input_number (min). Picked by domain so HA's
            // overloaded attribute names don't bleed across.
            minRaw = when (id.domain) {
                Domain.CLIMATE, Domain.WATER_HEATER -> raw.attributes["min_temp"].asDouble()
                Domain.HUMIDIFIER -> raw.attributes["min_humidity"].asDouble()
                Domain.NUMBER, Domain.INPUT_NUMBER -> raw.attributes["min"].asDouble() ?: 0.0
                else -> null
            },
            maxRaw = when (id.domain) {
                Domain.CLIMATE, Domain.WATER_HEATER -> raw.attributes["max_temp"].asDouble()
                Domain.HUMIDIFIER -> raw.attributes["max_humidity"].asDouble()
                Domain.NUMBER, Domain.INPUT_NUMBER -> raw.attributes["max"].asDouble() ?: 100.0
                else -> null
            },
            supportedColorModes = if (id.domain == Domain.LIGHT) extractColorModes(raw.attributes) else emptyList(),
            colorTempK = if (id.domain == Domain.LIGHT) raw.attributes["color_temp_kelvin"].asInt() else null,
            minColorTempK = if (id.domain == Domain.LIGHT) raw.attributes["min_color_temp_kelvin"].asInt() else null,
            maxColorTempK = if (id.domain == Domain.LIGHT) raw.attributes["max_color_temp_kelvin"].asInt() else null,
            hue = if (id.domain == Domain.LIGHT) extractHue(raw.attributes) else null,
            step = if (id.domain == Domain.NUMBER || id.domain == Domain.INPUT_NUMBER)
                raw.attributes["step"].asDouble() else null,
            effectList = if (id.domain == Domain.LIGHT) extractEffectList(raw.attributes) else emptyList(),
            effect = if (id.domain == Domain.LIGHT) raw.attributes["effect"].asString()?.takeIf { it != "None" } else null,
            attributesJson = raw.attributes,
            // Select-domain bits — options list + current option track via state.
            selectOptions = if (id.domain.isSelect) extractStringList(raw.attributes["options"]) else emptyList(),
            currentOption = if (id.domain.isSelect) raw.state.takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" } else null,
            mediaTitle = if (id.domain == Domain.MEDIA_PLAYER) raw.attributes["media_title"].asString() else null,
            mediaArtist = if (id.domain == Domain.MEDIA_PLAYER) raw.attributes["media_artist"].asString() else null,
            mediaAlbumName = if (id.domain == Domain.MEDIA_PLAYER) raw.attributes["media_album_name"].asString() else null,
            mediaDuration = if (id.domain == Domain.MEDIA_PLAYER) raw.attributes["media_duration"].asInt() else null,
            mediaPosition = if (id.domain == Domain.MEDIA_PLAYER) raw.attributes["media_position"].asInt() else null,
            mediaPositionUpdatedAt = if (id.domain == Domain.MEDIA_PLAYER) {
                raw.attributes["media_position_updated_at"].asString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            } else null,
            mediaPicture = if (id.domain == Domain.MEDIA_PLAYER) raw.attributes["entity_picture"].asString() else null,
            isVolumeMuted = id.domain == Domain.MEDIA_PLAYER &&
                (raw.attributes["is_volume_muted"] as? JsonPrimitive)?.content == "true",
            mediaSupportedFeatures = if (id.domain == Domain.MEDIA_PLAYER)
                raw.attributes["supported_features"].asInt() ?: 0
            else 0,
        )
        cache.update { it + (id to newState) }
    }

    /**
     * Percent computation that needs the entity *state* in addition to attributes —
     * NUMBER and INPUT_NUMBER carry their value in `state`, not in an attribute. Calls
     * out to [computePercent] for everything else.
     */
    private fun computePercentWithState(
        domain: Domain,
        attrs: kotlinx.serialization.json.JsonObject,
        stateStr: String,
    ): Int? = when (domain) {
        Domain.NUMBER, Domain.INPUT_NUMBER -> {
            val v = stateStr.toDoubleOrNull()
            val mn = attrs["min"].asDouble() ?: 0.0
            val mx = attrs["max"].asDouble() ?: 100.0
            if (v != null && mx > mn) {
                (((v - mn) / (mx - mn)) * 100.0).roundToInt().coerceIn(0, 100)
            } else null
        }
        else -> computePercent(domain, attrs)
    }

    private fun computePercent(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Int? = when (domain) {
        Domain.LIGHT -> attrs["brightness"].asInt()?.let(EntityState::normaliseLightBrightness)
        Domain.FAN -> attrs["percentage"].asInt()?.let(EntityState::normaliseFanPercentage)
        Domain.COVER -> attrs["current_position"].asInt()?.let(EntityState::normaliseCoverPosition)
        Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()?.let(EntityState::normaliseMediaVolume)
        Domain.HUMIDIFIER -> attrs["humidity"].asInt()?.coerceIn(0, 100)
        // Climate: scale target_temperature into 0..100 via min_temp/max_temp so the wheel's
        // percent abstraction maps naturally to "low end is cold, high end is hot". Falls
        // back to null (and the card stays on the switch-only path) when the range attrs
        // are missing on a particular HA install.
        Domain.CLIMATE, Domain.WATER_HEATER -> {
            val target = climateTargetTemp(attrs)
            val min = attrs["min_temp"].asDouble()
            val max = attrs["max_temp"].asDouble()
            if (target != null && min != null && max != null && max > min) {
                (((target - min) / (max - min)) * 100.0).roundToInt().coerceIn(0, 100)
            } else null
        }
        // Valve: same shape as cover — `current_position` 0..100 (closed..open).
        Domain.VALVE -> attrs["current_position"].asInt()?.coerceIn(0, 100)
        // Vacuums: percent abstraction doesn't apply (states are categorical).
        Domain.VACUUM -> null
        // Number / input_number: state is the value. We don't have access to row.state
        // here (computePercent takes only attrs), but we can read the entity's range
        // from attributes; the actual conversion uses minRaw/maxRaw at the VM layer
        // when sending the service call. For DISPLAY of percent, the caller threads
        // the current value through differently — see [EntityState.percent].
        Domain.NUMBER, Domain.INPUT_NUMBER -> null
        // No scalar — pure on/off / read-only / action.
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK,
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
        Domain.SENSOR, Domain.BINARY_SENSOR,
        Domain.SELECT, Domain.INPUT_SELECT -> null
    }

    /**
     * Read the supported_color_modes attribute as a list of mode-name strings. HA emits
     * this as a JSON array; an absent attribute (non-coloured bulb) returns empty so
     * downstream code can default the wheel-mode chips to brightness-only.
     */
    private fun extractColorModes(attrs: kotlinx.serialization.json.JsonObject): List<String> {
        val arr = attrs["supported_color_modes"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    /**
     * Read the light's effect_list attribute as a list of effect names. HA exposes it as
     * a JSON array of strings; an absent attribute (most plain bulbs) returns empty so
     * the card hides the effect chip entirely.
     */
    private fun extractEffectList(attrs: kotlinx.serialization.json.JsonObject): List<String> {
        val arr = attrs["effect_list"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    /**
     * Generic JSON-array-of-strings extractor — used for the `options` attribute on
     * select / input_select entities and any future attribute that ships as a flat
     * string array. Non-string elements are silently dropped rather than throwing so
     * a malformed HA payload doesn't lose the whole entity.
     */
    private fun extractStringList(el: kotlinx.serialization.json.JsonElement?): List<String> {
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    /**
     * Extract the current hue from `hs_color` if the bulb is reporting in colour mode.
     * HA exposes hs_color as [hue 0..360, saturation 0..100]. We only care about hue here
     * — saturation pinning is handled when we WRITE back at full saturation. Null when
     * the bulb isn't in a colour-aware mode.
     */
    private fun extractHue(attrs: kotlinx.serialization.json.JsonObject): Double? {
        val arr = attrs["hs_color"] as? kotlinx.serialization.json.JsonArray ?: return null
        val h = arr.firstOrNull() as? JsonPrimitive ?: return null
        return h.content.toDoubleOrNull()
    }

    /**
     * Best-effort climate target-temperature read. HA exposes `temperature` for single-
     * setpoint HVAC modes (heat or cool); in `heat_cool` mode the entity has separate
     * `target_temp_high` (cooling target) and `target_temp_low` (heating target). We
     * pick the high one as the user-driven setpoint — that's what the slider usually
     * represents on dashboards. Falls back to `current_temperature` only as a last
     * resort (it's not a target value but at least gives a sensible scaled position
     * when the entity has no settable target at all).
     */
    private fun climateTargetTemp(attrs: kotlinx.serialization.json.JsonObject): Double? =
        attrs["temperature"].asDouble()
            ?: attrs["target_temp_high"].asDouble()
            ?: attrs["target_temp_low"].asDouble()
            ?: attrs["current_temperature"].asDouble()

    private fun computeRaw(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Number? = when (domain) {
        Domain.LIGHT -> attrs["brightness"].asInt()
        Domain.FAN -> attrs["percentage"].asInt()
        Domain.COVER -> attrs["current_position"].asInt()
        Domain.VALVE -> attrs["current_position"].asInt()
        Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()
        Domain.HUMIDIFIER -> attrs["humidity"].asInt()
        // Climate's raw is the actual target_temperature for the card's display, with
        // the same fallback chain as computePercent so a `heat_cool` mode entity that
        // only exposes target_temp_high/low still renders sensibly. Water-heater
        // mirrors the climate path.
        Domain.CLIMATE, Domain.WATER_HEATER -> climateTargetTemp(attrs)
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK,
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
        Domain.BINARY_SENSOR, Domain.VACUUM,
        Domain.SELECT, Domain.INPUT_SELECT -> null
        // For plain sensors the *state* IS the reading — there's no attribute to read from.
        // The SensorCard renders the rawState string directly; we don't try to coerce it
        // into a Number here because that loses precision (e.g. "21.7" → 21) and locale
        // formatting (HA already sends a presentation-ready string).
        Domain.SENSOR -> null
        // Number / input_number: same as plain sensor — the entity state is the value.
        // Repurposing rawState string for display + threading it through the VM at
        // service-call time keeps the precision intact.
        Domain.NUMBER, Domain.INPUT_NUMBER -> null
    }

    /**
     * Whether the entity exposes a settable scalar (brightness/percentage/position/volume) that
     * the wheel can drive. Used to filter on/off-only entities out of the Favourites picker —
     * otherwise users see brightness % controls for switches dressed as lights, which the wheel
     * can change visually but HA silently ignores.
     */
    private fun supportsScalar(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Boolean = when (domain) {
        Domain.LIGHT -> {
            // `supported_color_modes` is the AUTHORITATIVE capability for a light — it lists
            // the modes the integration can drive. Non-dimmable lights have `["onoff"]` only;
            // anything else means at least brightness control. We trust it absolutely when
            // present (don't fall through to brightness-attribute checks, which lit up false
            // positives on non-dim lights when they were on with brightness=255).
            val supportedModes = (attrs["supported_color_modes"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            if (supportedModes.isNotEmpty()) {
                supportedModes.any { it != "onoff" }
            } else {
                // Older integrations don't expose supported_color_modes. Fall back to
                // color_mode then brightness as best-effort hints.
                val mode = attrs["color_mode"].asString()
                when {
                    mode == "onoff" -> false
                    mode != null -> true
                    attrs["brightness"] != null -> true
                    else -> false
                }
            }
        }
        // FanEntityFeature.SET_SPEED = bit 0 of supported_features.
        Domain.FAN -> ((attrs["supported_features"].asInt() ?: 0) and 1) != 0 ||
            attrs["percentage"] != null
        // CoverEntityFeature.SET_POSITION = bit 2.
        Domain.COVER -> ((attrs["supported_features"].asInt() ?: 0) and 4) != 0 ||
            attrs["current_position"] != null
        // MediaPlayerEntityFeature.VOLUME_SET = bit 2.
        Domain.MEDIA_PLAYER -> ((attrs["supported_features"].asInt() ?: 0) and 4) != 0 ||
            attrs["volume_level"] != null
        // Humidifiers always expose `set_humidity` as a service; the wheel drives that.
        // Treat presence of `humidity` attribute as authoritative — if it's missing
        // (a misbehaving integration) we still want a switch-card representation.
        Domain.HUMIDIFIER -> attrs["humidity"] != null
        // Climate: scalar when we have a temperature target AND the temperature range
        // (min/max). Without the range we can't map percent → °C, so the card falls back
        // to the switch-only path (turn_on / turn_off). ClimateEntityFeature.TARGET_TEMPERATURE
        // is bit 1 — we trust the supported_features bitmask AND the presence of min_temp.
        // Climate / water_heater: scalar when we have a temperature target AND a range.
        // Earlier this gated only on supported_features bit 1 (TARGET_TEMPERATURE) plus
        // min/max, but some integrations (notably MQTT-thermostats) forget the bit
        // while still exposing the attribute — fall back to climateTargetTemp() probing
        // the attributes themselves so those entities don't degrade to switch-only.
        Domain.CLIMATE, Domain.WATER_HEATER -> climateTargetTemp(attrs) != null &&
            attrs["min_temp"] != null && attrs["max_temp"] != null
        // Valve: same shape as cover — has the SET_POSITION bit (1<<1) or an explicit
        // current_position attribute. Falls back to switch (open_valve/close_valve)
        // when neither is present.
        Domain.VALVE -> ((attrs["supported_features"].asInt() ?: 0) and 2) != 0 ||
            attrs["current_position"] != null
        // number / input_number: always scalar — that's the entity's whole reason for
        // existing. Range comes from min/max attrs (defaulted to 0..100 if absent).
        Domain.NUMBER, Domain.INPUT_NUMBER -> true
        // Pure on/off domains — no scalar; rendered as switch cards.
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK -> false
        // Vacuums map naturally to switch cards (start/return-to-base on tap).
        Domain.VACUUM -> false
        // Action-only domains — no scalar; rendered as ActionCard tiles.
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON -> false
        // Sensors are read-only — rendered as SensorCard, no wheel.
        Domain.SENSOR, Domain.BINARY_SENSOR -> false
        // Select entities — settable but the value is a discrete option, not a 0..100
        // scalar. Returning false routes them away from the percent / switch paths into
        // the dedicated SelectCard.
        Domain.SELECT, Domain.INPUT_SELECT -> false
    }

    override fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>> =
        cache.map { it.filterKeys { id -> id in entities } }

    override suspend fun call(call: ServiceCall): Result<Unit> {
        // Optimistic update was already applied by the ViewModel — the repo just forwards.
        // Key includes the service name so rapid taps of distinct buttons on the same
        // entity (PLAY then NEXT then VOL+ on a media_player) don't cancel each other.
        debouncer.submit(call.target to call.service, call)
        return Result.success(Unit)
    }

    override suspend fun listAllEntities(): Result<List<EntityState>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured — sign out & reconnect from Settings.")
            // Pre-emptive refresh — if the cached access token is within 60 s of
            // expiry, swap it before issuing the call. Skips the round-trip-then-401
            // dance in the common case where the app's been idle ~30 minutes and the
            // user just opened the picker.
            refresher?.ensureFresh()
            // Try the request with the cached access token. On HTTP 401 (token expired
            // mid-app or in the background) ask the TokenRefresher for a fresh one and
            // retry once. Without this retry path the picker often greeted users with a
            // "401 for /api/states" error after the app sat idle past the 30-minute
            // access-token lifetime, even though the refresh-token was perfectly valid
            // and the WS pipeline would have self-healed via AuthLost. Restarting the
            // app worked because cold-start triggered a fresh auth flow; the retry here
            // gives that same recovery in-place without the user noticing.
            val body = fetchStatesBody(server.url) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.listAll", "401 → refreshed access token; retrying once")
                    fetchStatesBody(server.url)
                        ?: error("Home Assistant returned HTTP 401 for /api/states even after refresh — sign out & reconnect.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/states — sign out & reconnect.")
                }
            }
            // Parse the response as a List<JsonElement> first, then decode each row
            // independently. The earlier `decodeFromString<List<RawStateRow>>` was an
            // all-or-nothing parse: a single weird row (state field missing, attributes
            // shape unexpected, etc.) would throw and the entire entity list would be lost.
            // That was almost certainly why scenes occasionally vanished from the picker —
            // some scene entries in HA's response had shapes the strict decoder didn't
            // accept. Per-row decoding with a try/catch keeps the rest of the list
            // available and lets us log the offenders rather than silently empty the UI.
            val rowsJson = listStatesJson.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(body)
            R1Log.i("HaRepo.listAll", "raw rows from /api/states: ${rowsJson.size}")
            val rowSerializer = RawStateRow.serializer()
            val rows = rowsJson.mapNotNull { el ->
                runCatching { listStatesJson.decodeFromJsonElement(rowSerializer, el) }.getOrElse { t ->
                    val eid = (el as? kotlinx.serialization.json.JsonObject)?.get("entity_id")?.let {
                        (it as? JsonPrimitive)?.content
                    } ?: "<unparseable>"
                    R1Log.w("HaRepo.listAll", "skipping malformed row $eid: ${t.message}")
                    null
                }
            }
            // Quick visibility on what came back so the user can see scenes/sensors in
            // logcat if the UI ever drops them; keeps debugging cheap in the field.
            val countsByDomain = rows.groupingBy {
                it.entity_id.substringBefore('.', missingDelimiterValue = "")
            }.eachCount()
            R1Log.i("HaRepo.listAll", "decoded ${rows.size} rows; by domain=$countsByDomain")
            // Diagnostic — when a user reports missing entities of a particular kind,
            // log raw vs decoded counts per domain so the offender pops out of logcat
            // immediately. The raw count is from the JSON array elements that name
            // a supported domain; the decoded count is what survived per-row decode.
            val rawByDomain = rowsJson.groupingBy {
                val obj = it as? kotlinx.serialization.json.JsonObject
                val eid = (obj?.get("entity_id") as? JsonPrimitive)?.content.orEmpty()
                eid.substringBefore('.', missingDelimiterValue = "")
            }.eachCount()
            val rawSupported = rawByDomain.filterKeys { Domain.isSupportedPrefix(it) }
            val deltas = rawSupported.mapValues { (d, raw) -> raw - (countsByDomain[d] ?: 0) }
                .filterValues { it > 0 }
            if (deltas.isNotEmpty()) {
                R1Log.w("HaRepo.listAll", "decoder dropped per-row: $deltas (raw=$rawSupported)")
            }
            // Log unsupported domains separately so users investigating "where's my
            // entity?" can run `adb logcat -s HaRepo.listAll` and see exactly which
            // domain prefixes we're dropping (with counts). The supported set is
            // implicit via Domain.isSupportedPrefix.
            val unsupported = countsByDomain.filterKeys { !Domain.isSupportedPrefix(it) }
            if (unsupported.isNotEmpty()) {
                R1Log.i("HaRepo.listAll", "unsupported (dropped): $unsupported")
            }
            rows.mapNotNull { row ->
                val prefix = row.entity_id.substringBefore('.', missingDelimiterValue = "")
                if (!Domain.isSupportedPrefix(prefix)) return@mapNotNull null
                // Wrap the whole EntityState construction in a try/catch so one weird
                // row (a media_player whose `volume_level` is a JsonArray instead of a
                // primitive, a climate with malformed `min_temp`, etc.) doesn't drop
                // the entire entity. Log the offender so users can find it via
                // `adb logcat -s HaRepo.listAll`.
                runCatching {
                val id = EntityId(row.entity_id)
                val stateStr = row.stateStr
                val attrs = row.attrsObj
                val available = stateStr != "unavailable" && stateStr != "unknown"
                val pct = if (available) computePercentWithState(id.domain, attrs, stateStr) else null
                val rawNum = computeRaw(id.domain, attrs)
                    ?: if (id.domain == Domain.NUMBER || id.domain == Domain.INPUT_NUMBER) stateStr.toDoubleOrNull() else null
                EntityState(
                    id = id,
                    friendlyName = attrs["friendly_name"].asString() ?: row.entity_id.substringAfter('.'),
                    area = attrs["area_id"].asString(),
                    // Use the same domain-aware logic as `applyEvent` so REST seed matches
                    // event-driven cache updates. Inline rather than calling out so this
                    // function stays self-contained for testing.
                    isOn = when (id.domain) {
                        Domain.LIGHT, Domain.FAN, Domain.SWITCH, Domain.INPUT_BOOLEAN,
                        Domain.AUTOMATION, Domain.HUMIDIFIER -> stateStr.equals("on", ignoreCase = true)
                        Domain.COVER, Domain.VALVE -> stateStr.equals("open", ignoreCase = true)
                        Domain.MEDIA_PLAYER -> stateStr.equals("playing", ignoreCase = true)
                        Domain.LOCK -> stateStr.equals("unlocked", ignoreCase = true)
                        Domain.CLIMATE, Domain.WATER_HEATER ->
                            !stateStr.equals("off", ignoreCase = true) && available
                        Domain.SCRIPT -> stateStr.equals("on", ignoreCase = true)
                        Domain.SCENE, Domain.BUTTON, Domain.INPUT_BUTTON -> false
                        Domain.BINARY_SENSOR -> stateStr.equals("on", ignoreCase = true)
                        Domain.SENSOR -> false
                        Domain.NUMBER, Domain.INPUT_NUMBER -> false
                        Domain.VACUUM -> stateStr.equals("cleaning", ignoreCase = true) ||
                            stateStr.equals("returning", ignoreCase = true) ||
                            stateStr.equals("on", ignoreCase = true)
                        // Settable enums — no on/off concept.
                        Domain.SELECT, Domain.INPUT_SELECT -> false
                    },
                    percent = pct,
                    raw = rawNum,
                    lastChanged = runCatching { Instant.parse(row.last_changed ?: "") }.getOrDefault(Instant.now()),
                    isAvailable = available,
                    supportsScalar = supportsScalar(id.domain, attrs),
                    rawState = stateStr,
                    unit = attrs["unit_of_measurement"].asString()
                        ?: attrs["temperature_unit"].asString(),
                    deviceClass = attrs["device_class"].asString(),
                    minRaw = when (id.domain) {
                        Domain.CLIMATE, Domain.WATER_HEATER -> attrs["min_temp"].asDouble()
                        Domain.HUMIDIFIER -> attrs["min_humidity"].asDouble()
                        Domain.NUMBER, Domain.INPUT_NUMBER -> attrs["min"].asDouble() ?: 0.0
                        else -> null
                    },
                    maxRaw = when (id.domain) {
                        Domain.CLIMATE, Domain.WATER_HEATER -> attrs["max_temp"].asDouble()
                        Domain.HUMIDIFIER -> attrs["max_humidity"].asDouble()
                        Domain.NUMBER, Domain.INPUT_NUMBER -> attrs["max"].asDouble() ?: 100.0
                        else -> null
                    },
                    supportedColorModes = if (id.domain == Domain.LIGHT) extractColorModes(attrs) else emptyList(),
                    colorTempK = if (id.domain == Domain.LIGHT) attrs["color_temp_kelvin"].asInt() else null,
                    minColorTempK = if (id.domain == Domain.LIGHT) attrs["min_color_temp_kelvin"].asInt() else null,
                    maxColorTempK = if (id.domain == Domain.LIGHT) attrs["max_color_temp_kelvin"].asInt() else null,
                    hue = if (id.domain == Domain.LIGHT) extractHue(attrs) else null,
                    step = if (id.domain == Domain.NUMBER || id.domain == Domain.INPUT_NUMBER)
                        attrs["step"].asDouble() else null,
                    effectList = if (id.domain == Domain.LIGHT) extractEffectList(attrs) else emptyList(),
                    effect = if (id.domain == Domain.LIGHT) attrs["effect"].asString()?.takeIf { it != "None" } else null,
                    attributesJson = attrs,
                    // Select / input_select — options list from `options` attribute,
                    // current option is just the state string. Empty / null for
                    // other domains.
                    selectOptions = if (id.domain.isSelect) extractStringList(attrs["options"]) else emptyList(),
                    currentOption = if (id.domain.isSelect) stateStr.takeIf { it.isNotBlank() && it != "unknown" && it != "unavailable" } else null,
                    mediaTitle = if (id.domain == Domain.MEDIA_PLAYER) attrs["media_title"].asString() else null,
                    mediaArtist = if (id.domain == Domain.MEDIA_PLAYER) attrs["media_artist"].asString() else null,
                    mediaAlbumName = if (id.domain == Domain.MEDIA_PLAYER) attrs["media_album_name"].asString() else null,
                    mediaDuration = if (id.domain == Domain.MEDIA_PLAYER) attrs["media_duration"].asInt() else null,
                    mediaPosition = if (id.domain == Domain.MEDIA_PLAYER) attrs["media_position"].asInt() else null,
                    mediaPositionUpdatedAt = if (id.domain == Domain.MEDIA_PLAYER) {
                        attrs["media_position_updated_at"].asString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    } else null,
                    mediaPicture = if (id.domain == Domain.MEDIA_PLAYER) attrs["entity_picture"].asString() else null,
                    isVolumeMuted = id.domain == Domain.MEDIA_PLAYER &&
                        (attrs["is_volume_muted"] as? JsonPrimitive)?.content == "true",
                    mediaSupportedFeatures = if (id.domain == Domain.MEDIA_PLAYER)
                        attrs["supported_features"].asInt() ?: 0
                    else 0,
                )
                }.getOrElse { t ->
                    R1Log.w("HaRepo.listAll", "construction failed for ${row.entity_id}: ${t.message}")
                    null
                }
            }
        }
    }

    override suspend fun listAllEntitiesRawPrefixCounts(): Result<Map<String, Int>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val body = fetchStatesBody(server.url) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        fetchStatesBody(server.url)
                            ?: error("Home Assistant returned HTTP 401 for /api/states even after refresh.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/states — sign out & reconnect.")
                    }
                }
                // Pull just the entity_id from each row by inspecting the raw JSON
                // object — no per-row decoder, no supported-domain filter. The diagnostic
                // needs to show what HA SENT, not what we kept; if media_player.* is in
                // here but missing from listAllEntities's result, that proves the filter
                // is the issue. If it's missing from BOTH, the problem is upstream
                // (HA-side permissions / entity-level visibility).
                val rowsJson = listStatesJson.decodeFromString<List<kotlinx.serialization.json.JsonElement>>(body)
                rowsJson
                    .mapNotNull {
                        val obj = it as? kotlinx.serialization.json.JsonObject
                        val eid = (obj?.get("entity_id") as? JsonPrimitive)?.content
                        eid?.substringBefore('.', missingDelimiterValue = "")
                    }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
            }
        }

    /**
     * Issue a GET to [url] with the current access token. Returns null on HTTP 401 so
     * the caller can refresh + retry; throws on any other non-success. Shared with the
     * [listAllEntities] / [fetchHistory] paths so 401 self-heal works the same way for
     * both.
     */
    private suspend fun fetchHistoryBody(url: String): String? = withContext(Dispatchers.IO) {
        val t = tokens.load() ?: error("Authentication tokens missing — sign out & reconnect from Settings.")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) { "Home Assistant returned HTTP ${resp.code} for /api/history" }
            resp.body!!.string()
        }
    }

    /**
     * One-shot REST `GET /api/states` returning the response body, or null on HTTP 401
     * so the caller can attempt a token refresh + retry. Any other HTTP failure throws
     * (the runCatching at the call site surfaces it). Always reads the access token
     * fresh from the [tokens] store so a retry after [TokenRefresher.forceRefresh]
     * picks up the newly-rotated value.
     */
    private suspend fun fetchStatesBody(serverUrl: String): String? = withContext(Dispatchers.IO) {
        val t = tokens.load() ?: error("Authentication tokens missing — sign out & reconnect from Settings.")
        val req = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/states")
            .header("Authorization", "Bearer ${t.accessToken}")
            .build()
        http.newCall(req).execute().use { resp ->
            // 401 is special — the caller decides whether to refresh + retry. Any
            // other non-success is a hard error (404 on a missing /api endpoint,
            // 500 from HA, network error, etc.) and gets reported as-is.
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) { "Home Assistant returned HTTP ${resp.code} for /api/states" }
            resp.body!!.string()
        }
    }

    /**
     * Seeds the in-memory cache from a one-shot REST `GET /api/states` so the user sees current
     * values immediately after adding a favourite (subscribe_trigger only fires on the *next*
     * transition, so without this seed the card would sit at 0% until the user actually changes
     * the entity from elsewhere). Retries 3× with a short delay because the call right after
     * WS Connected sometimes races HA's REST stack on slow servers.
     */
    private suspend fun seedCacheFromHa() {
        val favIds = settings.settings.first().favorites
            .mapNotNull { runCatching { EntityId(it) }.getOrNull() }
            .toSet()
        if (favIds.isEmpty()) return
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = listAllEntities()
            result.fold(
                onSuccess = { all ->
                    // If the user signed out while this REST call was in flight, drop the
                    // results on the floor — otherwise we'd repopulate the cache that the
                    // URL-change observer just cleared, bleeding server-A state into server-B.
                    if (settings.settings.first().server == null) {
                        R1Log.w("HaRepo.seed", "server gone mid-seed; discarding ${all.size} entities")
                        return
                    }
                    val byId = all.filter { it.id in favIds }.associateBy { it.id }
                    if (byId.isNotEmpty()) {
                        // Only toast on the FIRST successful seed (i.e. when the cache was
                        // previously empty). Doing the emptiness check INSIDE update {} closes
                        // the race window where two concurrent seeds would both see "empty"
                        // and both fire the toast.
                        var wasEmpty = false
                        cache.update { current ->
                            wasEmpty = current.isEmpty()
                            current + byId
                        }
                        R1Log.i("HaRepo.seed", "seeded ${byId.size}/${favIds.size} favourites (attempt ${attempt + 1})")
                        if (wasEmpty) {
                            com.github.itskenny0.r1ha.core.util.Toaster.show("Loaded ${byId.size} entities")
                        }
                    } else {
                        R1Log.w("HaRepo.seed", "REST returned ${all.size} entities but none matched favourites")
                    }
                    return
                },
                onFailure = { t ->
                    lastError = t
                    R1Log.w("HaRepo.seed", "attempt ${attempt + 1} failed: ${t.message}")
                    delay(500L * (attempt + 1)) // 500ms, 1s, 1.5s
                },
            )
        }
        val msg = lastError?.message ?: "unknown error"
        R1Log.e("HaRepo.seed", "all retries failed: $msg", lastError)
        com.github.itskenny0.r1ha.core.util.Toaster.error("Couldn't load entities: $msg")
    }

    /** Single Json instance for /api/states deserialisation to avoid the per-call allocation lint. */
    private val listStatesJson = Json { ignoreUnknownKeys = true }

    override suspend fun fetchHistory(entityId: EntityId, hours: Int): Result<List<HistoryPoint>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                // Pre-emptive refresh on near-expiry — same reasoning as listAllEntities.
                refresher?.ensureFresh()
                val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
                // HA's history endpoint takes the ISO timestamp in the URL path. URL-encode
                // the entity_id even though current HA versions don't require it — defensive
                // against entity_ids that contain unusual characters in future versions.
                val sinceIso = since.toString()
                val url = "${server.url.trimEnd('/')}/api/history/period/$sinceIso" +
                    "?filter_entity_id=${java.net.URLEncoder.encode(entityId.value, "UTF-8")}" +
                    "&minimal_response&no_attributes"
                // Same 401 → refresh → retry path as listAllEntities — sensor charts
                // fired silently in the background while the user was on the card stack
                // were a common trigger for "history failed; chart blank" until the user
                // restarted the app.
                val body = fetchHistoryBody(url) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        R1Log.i("HaRepo.fetchHistory", "401 → refreshed access token; retrying once")
                        fetchHistoryBody(url)
                            ?: error("Home Assistant returned HTTP 401 for /api/history even after refresh — sign out & reconnect.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/history — sign out & reconnect.")
                    }
                }
                // HA returns a JSON array of arrays — outermost level is one entry per
                // requested entity (we only ask for one). Each inner entry is a state
                // snapshot. `minimal_response` strips the attribute payload after the
                // first sample which keeps the response small and parse fast.
                val outer = listStatesJson.decodeFromString<List<List<HistoryRow>>>(body)
                val first = outer.firstOrNull().orEmpty()
                first.mapNotNull { row ->
                    val state = row.state ?: return@mapNotNull null
                    val ts = row.last_changed ?: row.last_updated ?: return@mapNotNull null
                    val instant = runCatching { Instant.parse(ts) }.getOrNull() ?: return@mapNotNull null
                    HistoryPoint.fromRaw(state, instant)
                }
            }.onFailure { t ->
                // CancellationException is the normal flow-control signal when the
                // calling LaunchedEffect (SensorCard's history fetch) is cancelled
                // because the card was scrolled out of view — not an error, just the
                // coroutine being told to stop. Logging it at WARN spammed the toast
                // feed with 'coroutine scope left the composition' noise that drowned
                // out actual failures. Surface it at DEBUG instead so it stays
                // available for power users who turn the toast level down, and skip
                // entirely the most common 'JobCancellationException: …left the
                // composition' wording from Compose's lifecycle scope.
                if (t is kotlinx.coroutines.CancellationException ||
                    t.message?.contains("left the composition", ignoreCase = true) == true
                ) {
                    R1Log.d("HaRepo.fetchHistory", "${entityId.value}: cancelled (card no longer composed)")
                } else {
                    R1Log.w("HaRepo.fetchHistory", "${entityId.value}: ${t.message}")
                }
            }
        }

    /** Minimal row shape for /api/history; uses `minimal_response` so attributes are absent
     *  after the first sample. Both timestamp fields are nullable because HA omits one or
     *  the other depending on whether the sample is the first in the window. */
    @kotlinx.serialization.Serializable
    private data class HistoryRow(
        val state: String? = null,
        val last_changed: String? = null,
        val last_updated: String? = null,
    )

    override suspend fun conversationProcess(
        text: String,
        language: String?,
        conversationId: String?,
    ): Result<ConversationResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured.")
            refresher?.ensureFresh()
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("text", JsonPrimitive(text))
                if (!language.isNullOrBlank()) put("language", JsonPrimitive(language))
                if (!conversationId.isNullOrBlank()) put("conversation_id", JsonPrimitive(conversationId))
            }
            val url = "${server.url.trimEnd('/')}/api/conversation/process"
            val body = conversationCallBody(url, payload) ?: run {
                if (refresher?.forceRefresh() == true) {
                    R1Log.i("HaRepo.conversation", "401 → refreshed; retrying once")
                    conversationCallBody(url, payload)
                        ?: error("Home Assistant returned HTTP 401 for /api/conversation/process after refresh.")
                } else {
                    error("Home Assistant returned HTTP 401 for /api/conversation/process.")
                }
            }
            // Response shape: { response: { speech: { plain: { speech: "…" } },
            // response_type: "action_done" | "query_answer" | "error", … },
            // conversation_id: "…" }
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
                as? kotlinx.serialization.json.JsonObject
                ?: error("Unexpected conversation response shape")
            val convId = (root["conversation_id"] as? JsonPrimitive)?.content
            val response = root["response"] as? kotlinx.serialization.json.JsonObject
            val responseType = (response?.get("response_type") as? JsonPrimitive)?.content
            val speech = response
                ?.get("speech")?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("plain")?.let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("speech")?.let { (it as? JsonPrimitive)?.content }
                ?: "(no response)"
            ConversationResponse(
                speech = speech,
                conversationId = convId,
                responseType = responseType,
            )
        }.onFailure { t ->
            R1Log.w("HaRepo.conversation", "process failed: ${t.message}")
        }
    }

    override suspend fun fetchLogbook(hours: Int): Result<List<LogbookEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                refresher?.ensureFresh()
                val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
                val sinceIso = since.toString()
                // HA accepts the start time in the path; `end_time` is omitted so the
                // endpoint defaults to "now". The user wants the most recent activity,
                // so we let HA's default end window catch any events that landed in
                // the milliseconds since the request was constructed.
                val url = "${server.url.trimEnd('/')}/api/logbook/$sinceIso"
                val body = fetchHistoryBody(url) ?: run {
                    if (refresher?.forceRefresh() == true) {
                        R1Log.i("HaRepo.logbook", "401 → refreshed; retrying once")
                        fetchHistoryBody(url)
                            ?: error("Home Assistant returned HTTP 401 for /api/logbook after refresh.")
                    } else {
                        error("Home Assistant returned HTTP 401 for /api/logbook.")
                    }
                }
                // The endpoint returns a flat JSON array. We allow unknown keys
                // because HA includes context_id / context_user_id / message
                // depending on integration version — we only consume a fixed subset.
                val rows = logbookJson.decodeFromString<List<LogbookRow>>(body)
                rows.mapNotNull { row ->
                    val ts = row.`when`
                        ?: return@mapNotNull null
                    val instant = runCatching { Instant.parse(ts) }.getOrNull()
                        ?: return@mapNotNull null
                    val entityId = row.entity_id?.let { raw ->
                        // Defensive — HA can include entity_ids from domains we
                        // don't model (weather.*, person.*). Skip the EntityId
                        // constructor's domain-validation by trying it inside a
                        // runCatching; on miss, surface the event without a
                        // structured entity reference.
                        runCatching { EntityId(raw) }.getOrNull()
                    }
                    LogbookEntry(
                        timestamp = instant,
                        name = row.name ?: row.entity_id ?: "(unknown)",
                        message = row.message ?: "changed",
                        entityId = entityId,
                        domain = row.domain ?: row.entity_id?.substringBefore('.'),
                        state = row.state,
                    )
                }.sortedByDescending { it.timestamp } // newest first
            }.onFailure { t ->
                R1Log.w("HaRepo.logbook", "fetch failed: ${t.message}")
            }
        }

    /** Minimal row shape for /api/logbook. Every field is nullable
     *  because HA's logbook payloads vary by event type — an automation
     *  trigger row often lacks `state` but has `message`, a state-change
     *  row has both. `when` is the JSON key HA uses and is the only
     *  field we treat as required (skipping rows without it). */
    @kotlinx.serialization.Serializable
    private data class LogbookRow(
        val `when`: String? = null,
        val name: String? = null,
        val message: String? = null,
        val entity_id: String? = null,
        val domain: String? = null,
        val state: String? = null,
    )

    /** Lenient JSON for /api/logbook — same shape as [listStatesJson]: ignore
     *  fields HA adds in newer versions (context_id, context_user_id, icon).
     *  Defined as a property to keep the decoder hot rather than rebuilding
     *  it per call. */
    private val logbookJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** POST to /api/conversation/process. Returns null on HTTP 401 so the caller
     *  can refresh + retry. Same pattern as [fetchHistoryBody]. */
    private suspend fun conversationCallBody(
        url: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): String? = withContext(Dispatchers.IO) {
        val t = tokens.load() ?: error("Authentication tokens missing — sign out & reconnect from Settings.")
        val mediaType = "application/json".toMediaTypeOrNull()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${t.accessToken}")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) return@withContext null
            require(resp.isSuccessful) {
                "Home Assistant returned HTTP ${resp.code} for /api/conversation/process"
            }
            resp.body!!.string()
        }
    }

    private companion object {
        const val MAX_AUTHLOST_RETRIES = 3
        /**
         * Hard ceiling on how long the repository will wait for a `result` message after sending
         * a `call_service`. Set high enough to absorb a busy HA on a slow phone-to-broker link
         * (cover-set-position, media-volume-set on a Sonos group can take a couple of seconds),
         * low enough that the user knows within a sensible window if their command was lost.
         */
        const val CALL_TIMEOUT_MS = 15_000L
    }

    /**
     * Lenient shape for HA's /api/states rows. Originally `state: String` rejected any
     * row where HA reported state as a JSON number (some MQTT integrations leak the
     * native MQTT payload through without coercing it to a string), and the per-row
     * decoder would drop the entity entirely. JsonElement absorbs both forms; we
     * normalise to a plain String in [stateStr]. `attributes` is also JsonElement
     * rather than JsonObject so a misbehaving integration that emits an array (yes,
     * really) doesn't kill the row either.
     */
    @kotlinx.serialization.Serializable
    private data class RawStateRow(
        val entity_id: String,
        val state: kotlinx.serialization.json.JsonElement? = null,
        val attributes: kotlinx.serialization.json.JsonElement? = null,
        val last_changed: String? = null,
    ) {
        /** Normalised state string. Empty / null state in the wire payload reads as "unknown"
         *  so downstream availability/isOn computations treat the row consistently. */
        val stateStr: String
            get() = when (val s = state) {
                null -> "unknown"
                is JsonPrimitive -> s.content
                else -> s.toString()
            }

        /** Normalised attributes object. Anything that isn't a JSON object (null, array,
         *  primitive) reads as empty so attribute lookups return null. */
        val attrsObj: kotlinx.serialization.json.JsonObject
            get() = (attributes as? kotlinx.serialization.json.JsonObject)
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    private fun resubscribe() {
        scope.launch {
            val favs = settings.settings.first().favorites
            if (favs.isEmpty()) {
                // User cleared their favourites — tear down the existing subscription so HA
                // stops pushing events we no longer care about, instead of leaving a stale
                // trigger subscribed forever.
                subscriptionId?.let { old ->
                    val unsubId = ws.nextRequestId()
                    ws.send(HaOutbound.UnsubscribeEvents(id = unsubId, subscription = old))
                    subscriptionId = null
                }
                return@launch
            }
            val newId = ws.nextRequestId()
            ws.send(HaOutbound.SubscribeStateTrigger(id = newId, entityIds = favs))
            subscriptionId?.let { old ->
                val unsubId = ws.nextRequestId()
                ws.send(HaOutbound.UnsubscribeEvents(id = unsubId, subscription = old))
            }
            subscriptionId = newId
        }
    }
}
