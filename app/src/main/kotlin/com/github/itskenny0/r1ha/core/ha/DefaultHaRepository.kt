package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val cache = MutableStateFlow<Map<EntityId, EntityState>>(emptyMap())
    private val pendingCalls = ConcurrentHashMap<Int, CompletableDeferred<Result<Unit>>>()
    private var supervisorJob: Job? = null
    private var subscriptionId: Int? = null

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
        // best-effort: on error, the inbound listener completes the deferred; we don't await here
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
                        seedCacheFromHa()
                    }
                    is ConnectionState.Disconnected -> {
                        // The WS client always reports st.attempt=0 (it has no notion of
                        // consecutive failures); we track the run here.
                        val attempt = reconnectAttempt
                        reconnectAttempt = (attempt + 1).coerceAtMost(20)
                        reconnectLater(attempt)
                    }
                    is ConnectionState.AuthLost -> {
                        // Access token was rejected — most often because the 30-minute lifetime
                        // expired. Try one refresh; if it succeeds, reconnect. If the refresh
                        // itself fails (revoked refresh token, server unreachable, etc.) we stay
                        // in AuthLost and the user has to manually sign out & reconnect.
                        // Bounded to MAX_AUTHLOST_RETRIES to avoid tight-looping if HA keeps
                        // issuing access tokens that fail auth (rare misconfiguration).
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
                        seedCacheFromHa()
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
                    if (url == null) {
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
        val t = tokens.load() ?: return
        val base = server.url.trimEnd('/')
        val wsUrl = when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://")
            base.startsWith("http://")  -> base.replaceFirst("http://", "ws://")
            else -> base
        } + "/api/websocket"
        ws.connect(wsUrl, t.accessToken)
    }

    private fun reconnectLater(attempt: Int) {
        scope.launch {
            delay(backoff.delayForAttempt(attempt))
            connectFromSettings()
        }
    }

    private fun applyEvent(ev: HaInbound.Event) {
        val raw = ev.event.variables.trigger.toState
        val idStr = raw.entityId ?: ev.event.variables.trigger.entityId
        val prefix = idStr.substringBefore('.', missingDelimiterValue = "")
        if (!Domain.isSupportedPrefix(prefix)) return
        val id = EntityId(idStr)
        val isOn = raw.state.equals("on", ignoreCase = true) ||
            raw.state.equals("playing", ignoreCase = true) ||
            (raw.state.equals("open", ignoreCase = true))
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
        )
        cache.update { it + (id to newState) }
    }

    private fun computePercent(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Int? = when (domain) {
        Domain.LIGHT -> attrs["brightness"].asInt()?.let(EntityState::normaliseLightBrightness)
        Domain.FAN -> attrs["percentage"].asInt()?.let(EntityState::normaliseFanPercentage)
        Domain.COVER -> attrs["current_position"].asInt()?.let(EntityState::normaliseCoverPosition)
        Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()?.let(EntityState::normaliseMediaVolume)
    }

    private fun computeRaw(domain: Domain, attrs: kotlinx.serialization.json.JsonObject): Number? = when (domain) {
        Domain.LIGHT -> attrs["brightness"].asInt()
        Domain.FAN -> attrs["percentage"].asInt()
        Domain.COVER -> attrs["current_position"].asInt()
        Domain.MEDIA_PLAYER -> attrs["volume_level"].asDouble()
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
            val states = listStatesJson.decodeFromString<List<RawStateRow>>(body)
            states.mapNotNull { row ->
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
                    isOn = row.state.equals("on", ignoreCase = true) ||
                        row.state.equals("playing", ignoreCase = true) ||
                        row.state.equals("open", ignoreCase = true),
                    percent = pct,
                    raw = rawNum,
                    lastChanged = runCatching { Instant.parse(row.last_changed ?: "") }.getOrDefault(Instant.now()),
                    isAvailable = available,
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
                    val byId = all.filter { it.id in favIds }.associateBy { it.id }
                    if (byId.isNotEmpty()) {
                        cache.update { it + byId }
                        R1Log.i("HaRepo.seed", "seeded ${byId.size}/${favIds.size} favourites (attempt ${attempt + 1})")
                        com.github.itskenny0.r1ha.core.util.Toaster.show("Loaded ${byId.size} entities")
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

    private companion object {
        const val MAX_AUTHLOST_RETRIES = 3
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
