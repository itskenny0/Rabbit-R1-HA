package com.github.itskenny0.r1ha

import android.app.Application
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Toaster.init(this)
        // Wire the album-art cache to the app's cache dir so HA media_player
        // entity_pictures persist across launches. Disk hit ≈ 0 ms vs the ~300
        // ms LAN round-trip every fresh fetch costs on the R1's slow stack.
        com.github.itskenny0.r1ha.ui.components.AsyncBitmapCache.init(this)
        R1Log.i("App.onCreate", "application starting")
        appScope.launch {
            graph.haRepository.start()
            R1Log.i("App.onCreate", "haRepository.start() returned")
        }
        // Mirror the latest WheelKeySource into a volatile field so MainActivity's
        // dispatchKeyEvent (which runs on the UI thread and can't suspend) can honour the
        // user's "Key source" setting synchronously.
        appScope.launch {
            graph.settings.settings
                .map { it.wheel.keySource }
                .distinctUntilChanged()
                .collect { graph.latestKeySource = it }
        }
    }
}
