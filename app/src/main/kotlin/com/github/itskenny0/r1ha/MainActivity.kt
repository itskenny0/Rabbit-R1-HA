package com.github.itskenny0.r1ha

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.github.itskenny0.r1ha.ui.components.ToastHost
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.WheelKeySource
import com.github.itskenny0.r1ha.core.theme.LocalUiOptions
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.nav.AppNavGraph
import com.github.itskenny0.r1ha.nav.Routes

class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        R1Log.i("MainActivity.onCreate", "data=${intent?.data}")

        graph = (application as App).graph

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        handleOAuthCallback(intent)

        setContent {
            // Load the FIRST settings value synchronously (suspending) before we render the
            // NavHost. Otherwise we'd mount Onboarding briefly (initialValue.server is null)
            // and then jarringly switch to CardStack once the Flow emitted. produceState
            // returns null until the coroutine assigns the first value.
            val initialSettings by produceState<AppSettings?>(initialValue = null) {
                value = graph.settings.settings.first()
            }
            val settings by graph.settings.settings.collectAsStateWithLifecycle(
                initialValue = initialSettings ?: AppSettings(),
            )

            val initial = initialSettings
            if (initial == null) {
                // Splashscreen API keeps the system-level splash up until the activity is
                // ready to draw; we additionally render a blank surface to avoid any flash
                // until the first settings emission is in hand.
                Box(modifier = Modifier.fillMaxSize())
                return@setContent
            }
            // Cold-start app-shortcut delivery — if the user launched
            // us via a launcher long-press shortcut, the route to push
            // is sitting in the original intent's extras. Forward it
            // to the ShortcutBus so AppNavGraph picks it up on its
            // first compose tick. (onNewIntent handles subsequent
            // shortcut taps while the app is already running.)
            androidx.compose.runtime.LaunchedEffect(Unit) {
                intent.getStringExtra(EXTRA_INITIAL_ROUTE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { route ->
                        R1Log.i("MainActivity.setContent", "cold-start shortcut route: $route")
                        com.github.itskenny0.r1ha.core.util.ShortcutBus.request(route)
                    }
            }

            // Lock the start destination to the FIRST loaded value so theme changes, server
            // changes, etc. don't re-graph the NavHost mid-session. Two paths:
            //   - server == null         → ONBOARDING
            //   - server + startOnDashboard → DASHBOARD (wall-mounted / kiosk R1 path)
            //   - server + default        → CARD_STACK (handheld R1 path)
            val startDestination = remember(initial) {
                when {
                    initial.server == null -> Routes.ONBOARDING
                    initial.behavior.startOnDashboard -> Routes.DASHBOARD
                    else -> Routes.CARD_STACK
                }
            }
            val navController = rememberNavController()
            R1Log.d("MainActivity.setContent", "startDestination=$startDestination server=${initial.server?.url ?: "null"}")

            // Honour the user's "Hide status bar" toggle live — flipping it in Settings
            // applies immediately without an activity restart. WindowInsetsController is
            // the recommended API since SDK 30; we already require min 33 so no fallback
            // path is needed.
            androidx.compose.runtime.LaunchedEffect(settings.behavior.hideStatusBar) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                if (settings.behavior.hideStatusBar) {
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                    // Make the user-swipe-to-show transient (auto-hides after a beat) so
                    // peeking the bar to check the time doesn't permanently break the
                    // hidden state.
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
            }

            // Apply the toast-log level setting whenever it changes. R1Toast is a
            // process-scope object so we just update its flags; the bus host
            // composable reads them at push time. Off → toast UI is silent;
            // ERROR/WARN/INFO/DEBUG raise the threshold progressively.
            androidx.compose.runtime.LaunchedEffect(settings.behavior.toastLogLevel) {
                val level = settings.behavior.toastLogLevel
                com.github.itskenny0.r1ha.core.util.R1Toast.enabled =
                    level != com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.OFF
                com.github.itskenny0.r1ha.core.util.R1Toast.minLevel = when (level) {
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.OFF,
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.ERROR ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.ERROR
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.WARN ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.WARN
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.INFO ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.INFO
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.DEBUG ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.DEBUG
                }
            }
            R1ThemeHost(themeId = settings.theme) {
                CompositionLocalProvider(LocalUiOptions provides settings.ui) {
                    // Wrap the nav graph in a Box so the in-app ToastHost can
                    // overlay every navigated screen. The toast bus is process-
                    // scoped (see R1Toast); the host just renders whatever event
                    // it last received as long as the toast feature is enabled.
                    //
                    // On the R1 (≤ 360 dp wide) the ResponsiveColumn wrapper is
                    // a passthrough — every screen renders bit-for-bit
                    // identical to before. On larger displays (phones,
                    // tablets) the wrapper paints the bezel area with the
                    // theme background and centres each screen inside a
                    // bounded column so the layout stays legible instead of
                    // stretching widgets across a 1200 dp panel. Per-screen
                    // exceptions can opt out by reading currentWidthTier()
                    // directly (Cameras GRID does this to keep its grid
                    // column count adaptive without losing the centering).
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(com.github.itskenny0.r1ha.core.theme.R1.Bg),
                    ) {
                        com.github.itskenny0.r1ha.ui.layout.ResponsiveColumn {
                            AppNavGraph(
                                navController = navController,
                                startDestination = startDestination,
                                haRepository = graph.haRepository,
                                settings = graph.settings,
                                tokens = graph.tokens,
                                wheelInput = graph.wheelInput,
                            )
                        }
                        // Toast host sits OUTSIDE the responsive column so
                        // toasts always pop at the device's true screen
                        // edges, not the centred column's edges.
                        ToastHost()
                    }
                }
            }
        }
    }

    /**
     * If Android delivers the OAuth redirect to us as a deep-link intent (instead of being
     * intercepted by the WebView's `shouldOverrideUrlLoading`), surface it visibly so we can
     * debug. The WebView's interception is the primary path; this is a safety net.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        R1Log.i("MainActivity.onNewIntent", "data=${intent.data}")
        setIntent(intent) // so subsequent intent.getStringExtra reads see the new intent
        handleOAuthCallback(intent)
        // App-shortcut deep-link — fire the requested route through
        // the in-memory ShortcutBus so the nav-graph can pop it onto
        // its back stack. We don't navigate from here directly because
        // the NavController lives inside the Compose tree.
        intent.getStringExtra(EXTRA_INITIAL_ROUTE)?.takeIf { it.isNotBlank() }?.let { route ->
            R1Log.i("MainActivity.onNewIntent", "shortcut routed: $route")
            com.github.itskenny0.r1ha.core.util.ShortcutBus.request(route)
        }
    }

    /**
     * Coming back to the foreground after the app was backgrounded — kick a
     * reconnect if we're not currently connected. Backgrounded apps on R1
     * (and Android in general) frequently have their WS torn down by the
     * OS power saver; without an explicit nudge here the user would tap
     * back in, see stale data, and wonder why nothing updates until our
     * backoff timer fires. Cheap to call when already connected: the repo
     * short-circuits on the existing connection.
     */
    override fun onResume() {
        super.onResume()
        if (!::graph.isInitialized) return
        val conn = graph.haRepository.connection.value
        val needsKick = conn !is com.github.itskenny0.r1ha.core.ha.ConnectionState.Connected &&
            conn !is com.github.itskenny0.r1ha.core.ha.ConnectionState.Connecting &&
            conn !is com.github.itskenny0.r1ha.core.ha.ConnectionState.Authenticating
        if (needsKick) {
            R1Log.i("MainActivity.onResume", "kicking reconnect; conn=$conn")
            graph.haRepository.reconnectNow()
        }
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "r1ha" || data.host != "auth-callback") return
        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")
        if (!code.isNullOrBlank()) {
            R1Log.i("MainActivity.handleOAuth", "deep-link delivered code (len=${code.length})")
            Toaster.show("Deep-link delivered OAuth code (WebView should have caught this)", long = true)
        } else {
            R1Log.w("MainActivity.handleOAuth", "deep-link with no code; error=$error")
            Toaster.error("Deep-link with no code: error=$error")
        }
    }

    /** Wall-clock of the last VOLUME-driven wheel emit, per direction.
     *  Lets us throttle the framework's ~30 Hz auto-repeat down to a
     *  more sensible cadence while still letting a held button drive
     *  continuous motion on phones / tablets (the R1's physical wheel
     *  emits each detent as a discrete ACTION_DOWN so this only kicks
     *  in for VOLUME keycodes). */
    private var lastVolumeRepeatUp: Long = 0L
    private var lastVolumeRepeatDown: Long = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Honour the user's "Key source" setting (AUTO = both, DPAD = only D-pad keys, VOLUME =
        // only volume keys). Filtered-out keycodes fall through to super so the system can act
        // on them normally (e.g. volume keys actually change media volume when the user has
        // explicitly chosen DPAD only).
        val src = graph.latestKeySource
        val acceptsDpad = src == WheelKeySource.AUTO || src == WheelKeySource.DPAD
        val acceptsVolume = src == WheelKeySource.AUTO || src == WheelKeySource.VOLUME
        val isDown = event.action == KeyEvent.ACTION_DOWN
        // For physical VOLUME buttons, the framework synthesises auto-repeat events at ~30 Hz
        // when the user holds the button. Firing on every repeat would run brightness/volume
        // away in milliseconds; ignoring every repeat (the original behaviour) forced the user
        // to tap the button N times to make a meaningful adjustment on a phone/tablet. We
        // compromise: emit on every initial press (repeatCount == 0) AND throttle the
        // auto-repeat stream to ~8 Hz so a held button gives smooth, controllable motion.
        //
        // The R1's physical wheel maps to DPAD keycodes and emits each detent as a separate
        // ACTION_DOWN with repeatCount=0 — those bypass the throttle entirely so a fast spin
        // never loses an event.
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (acceptsDpad) {
                if (isDown) graph.wheelInput.emit(WheelEvent.Direction.UP)
                true
            } else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_VOLUME_UP -> if (acceptsVolume) {
                if (isDown && shouldEmitVolumeRepeat(event, isUp = true)) {
                    graph.wheelInput.emit(WheelEvent.Direction.UP)
                }
                true
            } else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (acceptsDpad) {
                if (isDown) graph.wheelInput.emit(WheelEvent.Direction.DOWN)
                true
            } else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (acceptsVolume) {
                if (isDown && shouldEmitVolumeRepeat(event, isUp = false)) {
                    graph.wheelInput.emit(WheelEvent.Direction.DOWN)
                }
                true
            } else super.dispatchKeyEvent(event)
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** Decide whether this VOLUME ACTION_DOWN should produce a wheel
     *  emit. The first press (repeatCount == 0) always fires. Subsequent
     *  framework-synthesised repeats are accepted at most every
     *  [VOLUME_REPEAT_MIN_MS] ms — calibrated to ~8 Hz which feels like
     *  a controllable manual dial spin rather than a runaway. */
    private fun shouldEmitVolumeRepeat(event: KeyEvent, isUp: Boolean): Boolean {
        if (event.repeatCount == 0) {
            // Reset the throttle so the first press is always honoured
            // AND the next auto-repeat measures its delta from this
            // moment instead of any stale previous-burst timestamp.
            if (isUp) lastVolumeRepeatUp = event.eventTime
            else lastVolumeRepeatDown = event.eventTime
            return true
        }
        val last = if (isUp) lastVolumeRepeatUp else lastVolumeRepeatDown
        val delta = event.eventTime - last
        if (delta < VOLUME_REPEAT_MIN_MS) return false
        if (isUp) lastVolumeRepeatUp = event.eventTime
        else lastVolumeRepeatDown = event.eventTime
        return true
    }

    companion object {
        /** Minimum gap between successive wheel emits when a VOLUME
         *  button is held. ~130 ms ≈ 7.7 Hz — the same cadence a
         *  practised thumb on the R1's physical wheel can manage, so
         *  the held-volume-button feel matches a manual spin. */
        private const val VOLUME_REPEAT_MIN_MS = 130L

        /** Intent extra used by the app-shortcut definitions (see
         *  res/xml/shortcuts.xml) to ask MainActivity to deep-link
         *  to a specific top-level route on launch. The value is a
         *  bare route name (e.g. "search", "assist") that AppNavGraph
         *  resolves via Routes constants. */
        const val EXTRA_INITIAL_ROUTE = "initial_route"
    }
}
