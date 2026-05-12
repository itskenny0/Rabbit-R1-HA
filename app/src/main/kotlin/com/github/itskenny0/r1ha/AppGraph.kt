package com.github.itskenny0.r1ha

import android.content.Context
import com.github.itskenny0.r1ha.core.ha.DefaultHaRepository
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaWebSocketClient
import com.github.itskenny0.r1ha.core.ha.TokenRefresher
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency-injection container. One instance lives on [App]; activities/fragments
 * access it via `(application as App).graph`.
 *
 * Construction is lazy so the first access triggers real allocation only once.
 */
class AppGraph(context: Context) {

    private val appContext: Context = context.applicationContext

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // WebSocket ping frames every 30s: detects half-open connections that the OS or
            // intermediate routers silently dropped (very common on Wi-Fi after device sleep).
            // OkHttp surfaces a missing PONG as onFailure, which our state machine treats as
            // a Disconnected and schedules a backoff reconnect.
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val settings: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val tokens: TokenStore by lazy {
        TokenStore(appContext)
    }

    val wsClient: HaWebSocketClient by lazy {
        // Share the OkHttpClient so the WS connection inherits its 30-second ping interval —
        // the no-arg HaWebSocketClient constructor builds a fresh client without it, which
        // left the production WS unable to detect half-open connections after device sleep.
        HaWebSocketClient(
            http = okHttp,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    val tokenRefresher: TokenRefresher by lazy {
        TokenRefresher(http = okHttp, settings = settings, tokens = tokens)
    }

    val haRepository: HaRepository by lazy {
        DefaultHaRepository(
            ws = wsClient,
            http = okHttp,
            settings = settings,
            tokens = tokens,
            refresher = tokenRefresher,
        )
    }

    val wheelInput: WheelInput by lazy {
        WheelInput()
    }

    /**
     * Latest [WheelKeySource] setting, kept up to date by a collector in [App.onCreate]. Read
     * from `MainActivity.dispatchKeyEvent`, which runs on the main thread and can't await a
     * suspend operation — so this volatile cache is the synchronous source of truth.
     */
    @Volatile
    var latestKeySource: WheelKeySource = WheelKeySource.AUTO
}
