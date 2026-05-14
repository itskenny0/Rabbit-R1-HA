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
        // Install a JVM-wide uncaught-exception handler that copies the
        // stack trace into R1LogBuffer BEFORE chaining to the previous
        // handler (which lets Android's default behaviour — crash dialog +
        // process death — proceed). Means the dev menu's log viewer can
        // surface the crash that just happened on next launch, and the
        // user can EXPORT the logs to a file via SAF for diagnostics.
        // Without this, every crash on the R1 was a black box; logcat isn't
        // accessible to most users.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                R1Log.e(
                    "App.uncaught",
                    "thread=${thread.name} ex=${ex::class.java.name}: ${ex.message}",
                    ex,
                )
                // Write the crash trace + recent log buffer to a file in
                // filesDir so the dev menu's 'LAST CRASH' button can surface
                // it after restart. Without this every crash is a black box
                // on the R1 — logcat isn't accessible to most users.
                val report = buildString {
                    append("R1HA crash · ").append(java.time.Instant.now().toString()).append('\n')
                    append("App ").append(BuildConfig.VERSION_NAME).append(" (")
                        .append(BuildConfig.VERSION_CODE).append(")\n")
                    append("Thread: ").append(thread.name).append('\n')
                    append("Exception: ").append(ex::class.java.name)
                    ex.message?.let { append(": ").append(it) }
                    append("\n\nSTACK TRACE:\n")
                    append(ex.stackTraceToString())
                    append("\n\nRECENT LOGS (newest first):\n")
                    val logs = com.github.itskenny0.r1ha.core.util.R1LogBuffer.snapshot().reversed()
                    for (e in logs.take(100)) {
                        val ts = java.time.Instant.ofEpochMilli(e.timestampMillis).toString()
                        append("[$ts] ").append(e.level).append(' ').append(e.tag)
                            .append(" — ").append(e.message).append('\n')
                    }
                }
                java.io.File(filesDir, "last_crash.txt").writeText(report, Charsets.UTF_8)
            }
            previousHandler?.uncaughtException(thread, ex)
        }
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
