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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

    /** Tracks consecutive reconnect attempts so BackoffPolicy actually backs off. */
    @Volatile private var reconnectAttempt: Int = 0

    /**
     * Tracks AuthLost-driven refresh attempts so we don't tight-loop if a misconfigured HA
     * keeps issuing access tokens that fail auth. Reset on Connected.
     */
    @Volatile private var authLostRefreshAttempt: Int = 0

    private val debouncer = DebouncedCaller<EntityId, ServiceCall>(scope, debounceMillis = 120) { _, call ->
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
            // R1Log so dev builds get the full picture; a short user-visible toast so they
            // know their action didn't take. Use objectId (e.g. "kitchen_lamp") not entity_id
            // (e.g. "light.kitchen_lamp") so the toast is readable on a 240-px display.
            R1Log.w("HaRepo.call", "${call.target.value}/${call.service} failed: ${t.message}")
            com.github.itskenny0.r1ha.core.util.Toaster.show(
                "Couldn't update ${call.target.objectId}: ${t.message ?: "unknown error"}",
            )
            // Tell the ViewModel so it can roll back the optimistic override — the slider
            // bounces back to HA's last-known value instead of sitting stuck on the user's
            // intent. tryEmit is fine: the buffer is bounded with DROP_OLDEST.
            _callFailures.tryEmit(call.target)
        }
    }

    override suspend fun start() {
        if (supervisorJob != null) return
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
            com.github.itskenny0.r1ha.core.util.Toaster.show(
                "Authentication tokens missing — open Settings → Sign out & reconnect",
                long = true,
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
        pendingReconnect = scope.launch {
            delay(backoff.delayForAttempt(attempt))
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
            Domain.COVER -> raw.state.equals("open", ignoreCase = true)
            Domain.MEDIA_PLAYER -> raw.state.equals("playing", ignoreCase = true)
            Domain.LOCK -> raw.state.equals("unlocked", ignoreCase = true)
            Domain.CLIMATE -> !raw.state.equals("off", ignoreCase = true) &&
                raw.state != "unavailable" && raw.state != "unknown"
            // Scripts have an "on" state while they're executing. Scene/button never get
            // a meaningful on state — their state attribute is a last-fired timestamp.
            Domain.SCRIPT -> raw.state.equals("on", ignoreCase = true)
            Domain.SCENE, Domain.BUTTON -> false
            // binary_sensor uses "on"/"off" by HA convention — "on" means the triggered
            // state (door open, motion detected, leak found). Plain sensor entities have
            // numeric/string readings and don't have a meaningful on/off mapping.
            Domain.BINARY_SENSOR -> raw.state.equals("on", ignoreCase = true)
            Domain.SENSOR -> false
        }
        val available = raw.state != "unavailable" && raw.state != "unknown"
        val pct = computePercent(id.domain, raw.attributes)
        val rawNum = computeRaw(id.domain, raw.attributes)
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
            unit = raw.attributes["unit_of_measurement"].asString(),
            deviceClass = raw.attributes["device_class"].asString(),
        )
        cache.update { it + (id to newState) }
    }

    private fun computePercent(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Int? = when (domain) {
        Domain.LIGHT -> attrs["brightness"].asInt()?.let(EntityState::normaliseLightBrightness)
        Domain.FAN -> attrs["percentage"].asInt()?.let(EntityState::normaliseFanPercentage)
        Domain.COVER -> attrs["current_position"].asInt()?.let(EntityState::normaliseCoverPosition)
        Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()?.let(EntityState::normaliseMediaVolume)
        Domain.HUMIDIFIER -> attrs["humidity"].asInt()?.coerceIn(0, 100)
        // No scalar — these domains are pure on/off (climate's target_temperature could be
        // driven, but mapping it through 0..100 requires min_temp/max_temp range plumbing
        // that's out of scope for the current release).
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK,
        Domain.CLIMATE, Domain.SCENE, Domain.SCRIPT, Domain.BUTTON,
        Domain.SENSOR, Domain.BINARY_SENSOR -> null
    }

    private fun computeRaw(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Number? = when (domain) {
        Domain.LIGHT -> attrs["brightness"].asInt()
        Domain.FAN -> attrs["percentage"].asInt()
        Domain.COVER -> attrs["current_position"].asInt()
        Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()
        Domain.HUMIDIFIER -> attrs["humidity"].asInt()
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK,
        Domain.CLIMATE, Domain.SCENE, Domain.SCRIPT, Domain.BUTTON,
        Domain.BINARY_SENSOR -> null
        // For plain sensors the *state* IS the reading — there's no attribute to read from.
        // The SensorCard renders the rawState string directly; we don't try to coerce it
        // into a Number here because that loses precision (e.g. "21.7" → 21) and locale
        // formatting (HA already sends a presentation-ready string).
        Domain.SENSOR -> null
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
        // Pure on/off domains — no scalar; rendered as switch cards.
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION, Domain.LOCK, Domain.CLIMATE -> false
        // Action-only domains — no scalar; rendered as ActionCard tiles.
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON -> false
        // Sensors are read-only — rendered as SensorCard, no wheel.
        Domain.SENSOR, Domain.BINARY_SENSOR -> false
    }

    override fun observe(entities: Set<EntityId>): Flow<Map<EntityId, EntityState>> =
        cache.map { it.filterKeys { id -> id in entities } }

    override suspend fun call(call: ServiceCall): Result<Unit> {
        // Optimistic update was already applied by the ViewModel — the repo just forwards.
        debouncer.submit(call.target, call)
        return Result.success(Unit)
    }

    override suspend fun listAllEntities(): Result<List<EntityState>> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settings.settings.first()
            val server = s.server ?: error("Server URL not configured — sign out & reconnect from Settings.")
            val t = tokens.load() ?: error("Authentication tokens missing — sign out & reconnect from Settings.")
            val req = Request.Builder()
                .url("${server.url.trimEnd('/')}/api/states")
                .header("Authorization", "Bearer ${t.accessToken}")
                .build()
            val body = http.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "Home Assistant returned HTTP ${resp.code} for /api/states" }
                resp.body!!.string()
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
            rows.mapNotNull { row ->
                val prefix = row.entity_id.substringBefore('.', missingDelimiterValue = "")
                if (!Domain.isSupportedPrefix(prefix)) return@mapNotNull null
                val id = EntityId(row.entity_id)
                val available = row.state != "unavailable" && row.state != "unknown"
                val attrs = row.attributes ?: kotlinx.serialization.json.JsonObject(emptyMap())
                val pct = if (available) computePercent(id.domain, attrs) else null
                val rawNum = computeRaw(id.domain, attrs)
                EntityState(
                    id = id,
                    friendlyName = attrs["friendly_name"].asString() ?: row.entity_id.substringAfter('.'),
                    area = attrs["area_id"].asString(),
                    // Use the same domain-aware logic as `applyEvent` so REST seed matches
                    // event-driven cache updates. Inline rather than calling out so this
                    // function stays self-contained for testing.
                    isOn = when (id.domain) {
                        Domain.LIGHT, Domain.FAN, Domain.SWITCH, Domain.INPUT_BOOLEAN,
                        Domain.AUTOMATION, Domain.HUMIDIFIER -> row.state.equals("on", ignoreCase = true)
                        Domain.COVER -> row.state.equals("open", ignoreCase = true)
                        Domain.MEDIA_PLAYER -> row.state.equals("playing", ignoreCase = true)
                        Domain.LOCK -> row.state.equals("unlocked", ignoreCase = true)
                        Domain.CLIMATE -> !row.state.equals("off", ignoreCase = true) && available
                        Domain.SCRIPT -> row.state.equals("on", ignoreCase = true)
                        Domain.SCENE, Domain.BUTTON -> false
                        Domain.BINARY_SENSOR -> row.state.equals("on", ignoreCase = true)
                        Domain.SENSOR -> false
                    },
                    percent = pct,
                    raw = rawNum,
                    lastChanged = runCatching { Instant.parse(row.last_changed ?: "") }.getOrDefault(Instant.now()),
                    isAvailable = available,
                    supportsScalar = supportsScalar(id.domain, attrs),
                    rawState = row.state,
                    unit = attrs["unit_of_measurement"].asString(),
                    deviceClass = attrs["device_class"].asString(),
                )
            }
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
        com.github.itskenny0.r1ha.core.util.Toaster.show("Couldn't load entities: $msg", long = true)
    }

    /** Single Json instance for /api/states deserialisation to avoid the per-call allocation lint. */
    private val listStatesJson = Json { ignoreUnknownKeys = true }

    override suspend fun fetchHistory(entityId: EntityId, hours: Int): Result<List<HistoryPoint>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val s = settings.settings.first()
                val server = s.server ?: error("Server URL not configured.")
                val t = tokens.load() ?: error("Authentication tokens missing.")
                val since = Instant.now().minusSeconds(hours.toLong() * 3600L)
                // HA's history endpoint takes the ISO timestamp in the URL path. URL-encode
                // the entity_id even though current HA versions don't require it — defensive
                // against entity_ids that contain unusual characters in future versions.
                val sinceIso = since.toString()
                val url = "${server.url.trimEnd('/')}/api/history/period/$sinceIso" +
                    "?filter_entity_id=${java.net.URLEncoder.encode(entityId.value, "UTF-8")}" +
                    "&minimal_response&no_attributes"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${t.accessToken}")
                    .build()
                val body = http.newCall(req).execute().use { resp ->
                    require(resp.isSuccessful) {
                        "Home Assistant returned HTTP ${resp.code} for /api/history"
                    }
                    resp.body!!.string()
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
                R1Log.w("HaRepo.fetchHistory", "${entityId.value}: ${t.message}")
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

    @kotlinx.serialization.Serializable
    private data class RawStateRow(
        val entity_id: String,
        val state: String,
        val attributes: kotlinx.serialization.json.JsonObject? = null,
        val last_changed: String? = null,
    )

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
