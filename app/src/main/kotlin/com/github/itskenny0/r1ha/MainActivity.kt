package com.github.itskenny0.r1ha

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.nav.AppNavGraph
import com.github.itskenny0.r1ha.nav.Routes
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        graph = (application as App).graph

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val settings by graph.settings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            val startDestination = if (settings.server != null) Routes.CARD_STACK else Routes.ONBOARDING
            val navController = rememberNavController()

            R1ThemeHost(themeId = settings.theme) {
                AppNavGraph(
                    navController = navController,
                    startDestination = startDestination,
                    haRepository = graph.haRepository,
                    settings = graph.settings,
                    tokens = graph.tokens,
                    wheelInput = graph.wheelInput,
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (isDown) graph.wheelInput.emit(WheelEvent.Direction.UP)
                isDown || isUp
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (isDown) graph.wheelInput.emit(WheelEvent.Direction.DOWN)
                isDown || isUp
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
