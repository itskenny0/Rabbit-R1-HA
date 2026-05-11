package com.github.itskenny0.r1ha

import android.app.Application
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Toaster.init(this)
        R1Log.i("App.onCreate", "application starting")
        appScope.launch {
            graph.haRepository.start()
            R1Log.i("App.onCreate", "haRepository.start() returned")
        }
    }
}
