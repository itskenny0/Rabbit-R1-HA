package com.github.itskenny0.r1ha

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

            // Lock the start destination to the FIRST loaded value so theme changes, server
            // changes, etc. don't re-graph the NavHost mid-session.
            val startDestination = remember(initial) {
                if (initial.server != null) Routes.CARD_STACK else Routes.ONBOARDING
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
                    Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                        AppNavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            haRepository = graph.haRepository,
                            settings = graph.settings,
                            tokens = graph.tokens,
                            wheelInput = graph.wheelInput,
                        )
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
        handleOAuthCallback(intent)
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
            Toaster.show("Deep-link with no code: error=$error", long = true)
        }
    }

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
        // when the user holds the button — those have repeatCount > 0 and would run the
        // brightness away if we fired on each one. The wheel emits each detent as a separate
        // ACTION_DOWN with repeatCount=0, so we DO NOT apply this filter to DPAD keycodes
        // (the wheel's typical mapping) — a buggy driver that emits repeatCount>0 per detent
        // would otherwise silently lose every other wheel event.
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (acceptsDpad) {
                if (isDown) graph.wheelInput.emit(WheelEvent.Direction.UP)
                true
            } else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_VOLUME_UP -> if (acceptsVolume) {
                if (isDown && event.repeatCount == 0) graph.wheelInput.emit(WheelEvent.Direction.UP)
                true
            } else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (acceptsDpad) {
                if (isDown) graph.wheelInput.emit(WheelEvent.Direction.DOWN)
                true
            } else super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (acceptsVolume) {
                if (isDown && event.repeatCount == 0) graph.wheelInput.emit(WheelEvent.Direction.DOWN)
                true
            } else super.dispatchKeyEvent(event)
            else -> super.dispatchKeyEvent(event)
        }
    }
}
