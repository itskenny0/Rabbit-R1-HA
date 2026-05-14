package com.github.itskenny0.r1ha.core.util

import android.app.Application

/**
 * Process-scoped toast helper. Initialised once from [com.github.itskenny0.r1ha.App.onCreate];
 * any code (including ViewModels and repositories) can then `Toaster.show("…")` without
 * holding its own Context reference.
 *
 * **Why no Android Toast.** The R1's 240×320 display truncates the OS Toast at ~28 chars
 * mid-sentence — failure messages like "Validation error: Entity media_player.foo doesn't
 * support media_next_track" were getting clipped before the user could read what went
 * wrong. Every Toaster.show now routes exclusively through [R1Toast.userPush] which
 * renders an expandable in-app toast (tap to see the full text) via [ui.components.ToastHost]
 * mounted at the activity root. We keep the [init] entry point for back-compat with the
 * App.onCreate wiring even though we no longer need the Application reference.
 */
internal object Toaster {
    @Volatile private var app: Application? = null

    fun init(application: Application) { app = application }

    /**
     * Show a single-line user-facing message. The text is used as both the inline
     * preview and the expanded body — callers with separate short / long forms should
     * prefer [showExpandable].
     */
    fun show(message: String, @Suppress("UNUSED_PARAMETER") long: Boolean = false) {
        R1Toast.userPush(R1Toast.Level.WARN, "ui", message, message)
    }

    /**
     * Show a toast with distinct inline + expanded text. The inline preview is what
     * the host renders by default in the toast strip; tapping it expands to [fullText],
     * which can be arbitrarily long (HA error messages can run for paragraphs). Use
     * this variant whenever you have a stack-trace-grade message that would otherwise
     * have to be truncated for the preview.
     */
    fun showExpandable(shortText: String, fullText: String) {
        R1Toast.userPush(R1Toast.Level.WARN, "ui", shortText, fullText)
    }
}
