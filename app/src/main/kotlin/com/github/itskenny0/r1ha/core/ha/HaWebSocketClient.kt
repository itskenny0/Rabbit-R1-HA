package com.github.itskenny0.r1ha.core.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-level Home Assistant WebSocket client. Owns the WS lifecycle, performs auth handshake,
 * exposes inbound messages as a SharedFlow, and accepts outbound messages via [send].
 *
 * Reconnect/backoff/keepalive are layered on by HaRepository (Milestone 6); this class focuses on
 * "one connection, cleanly handled."
 */
class HaWebSocketClient internal constructor(
    private val http: OkHttpClient,
    internal val scope: CoroutineScope,
) {
    constructor() : this(http = OkHttpClient(), scope = CoroutineScope(SupervisorJob()))

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<HaInbound>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val inbound: SharedFlow<HaInbound> = _inbound.asSharedFlow()

    private val nextId = AtomicInteger(1)
    fun nextRequestId(): Int = nextId.getAndIncrement()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var receiverJob: Job? = null
    private val outgoing = Channel<HaOutbound>(capacity = Channel.UNLIMITED)

    fun connect(url: String, accessToken: String) {
        // Allow connect from Idle, Disconnected, AND AuthLost: when the repository succeeds at
        // refreshing the access token after an auth-rejected handshake, it has to be able to
        // reconnect even though the state is still pinned at AuthLost.
        val canReconnect = _state.value is ConnectionState.Idle ||
            _state.value is ConnectionState.Disconnected ||
            _state.value is ConnectionState.AuthLost
        if (!canReconnect) return
        _state.value = ConnectionState.Connecting
        val req = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                // webSocket was already set from the http.newWebSocket() return value below so
                // the onFailure/onClosed guards work even if the connection fails before onOpen
                // is delivered. Nothing to do here beyond advancing the state.
                _state.value = ConnectionState.Authenticating
                receiverJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    for (msg in outgoing) ws.send(HaJson.encodeToString(msg))
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                // Ignore messages from a WebSocket that has been replaced or torn down — without
                // this guard, a late AuthOk from a previous connection could bump the state back
                // to Connected after the user has signed out (or while a new connection is mid-
                // authenticating).
                if (this@HaWebSocketClient.webSocket !== ws) return
                val msg = runCatching { HaJson.decodeFromString<HaInbound>(text) }
                    .getOrElse { HaInbound.Unknown }
                when (msg) {
                    is HaInbound.AuthRequired -> ws.send(HaJson.encodeToString<HaOutbound>(HaOutbound.Auth(accessToken)))
                    is HaInbound.AuthOk -> _state.value = ConnectionState.Connected(msg.haVersion)
                    is HaInbound.AuthInvalid -> {
                        _state.value = ConnectionState.AuthLost(msg.message)
                        ws.close(1000, "auth_invalid")
                    }
                    else -> Unit
                }
                _inbound.tryEmit(msg)
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { /* HA only sends text */ }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                // Ignore stale callbacks: if disconnect() already ran (webSocket=null) or a new
                // connection has replaced this one (webSocket != ws), don't downgrade the state
                // to Disconnected — that would briefly flash "Disconnected (server closed)" on
                // the About page right after sign-out.
                if (this@HaWebSocketClient.webSocket !== ws) return
                // Preserve a sticky AuthLost — we just called ws.close() ourselves in response
                // to AuthInvalid; the resulting onClosed would otherwise overwrite a meaningful
                // "auth invalid" message with the generic "server closed".
                if (_state.value !is ConnectionState.AuthLost) {
                    _state.value = ConnectionState.Disconnected(ConnectionState.Cause.ServerClosed, attempt = 0)
                }
                receiverJob?.cancel(); webSocket = null
            }
            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                if (this@HaWebSocketClient.webSocket !== ws) return
                if (_state.value !is ConnectionState.AuthLost) {
                    _state.value = ConnectionState.Disconnected(ConnectionState.Cause.Error(t), attempt = 0)
                }
                receiverJob?.cancel(); webSocket = null
            }
        }
        // Store the WebSocket immediately so the listener's stale-callback guard works for
        // failures that fire before onOpen (e.g. DNS failure, TLS handshake error).
        webSocket = http.newWebSocket(req, listener)
    }

    /** Enqueue an outbound message. Safe to call before [connect] has completed; the queue drains once connected. */
    fun send(msg: HaOutbound) {
        outgoing.trySend(msg)
    }

    fun disconnect(code: Int = 1000, reason: String = "client_disconnect") {
        webSocket?.close(code, reason)
        webSocket = null
        receiverJob?.cancel()
        // Drain any queued outbound messages so they don't get replayed against a different
        // server when the user signs in again. This was an actual leak: a wheel debounce that
        // fired right before sign-out would sit in the channel and execute on the next
        // server's entities (mostly errors, occasionally surprising state changes if entity
        // IDs collided).
        while (outgoing.tryReceive().isSuccess) { /* discard */ }
        _state.value = ConnectionState.Idle
    }
}
