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
    }
}
