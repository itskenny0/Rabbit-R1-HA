package com.github.itskenny0.r1ha.core.util

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Process-scoped toast helper. Initialised once from [com.github.itskenny0.r1ha.App.onCreate];
 * any code (including ViewModels and repositories) can then `Toaster.show("…")` without holding
 * its own Context reference — the application context is safe to hold for the process lifetime.
 *
 * Toasts always post to the main thread, so callers don't need to care about their current
 * dispatcher.
 */
internal object Toaster {
    @Volatile private var app: Application? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(application: Application) { app = application }

    fun show(message: String, long: Boolean = false) {
        val ctx = app ?: return
        val duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        mainHandler.post {
            Toast.makeText(ctx, message, duration).show()
        }
        // Also push to the in-app R1Toast bus at WARN — user-facing toasts are
        // typically failure feedback (call rejected, settings save failed) and the
        // user might want to tap them open for the full text on the R1's tiny
        // display. Pushed unconditionally; R1Toast's own enabled / minLevel gates
        // whether the host actually surfaces it.
        R1Toast.push(R1Toast.Level.WARN, "ui", message, message)
    }
}
