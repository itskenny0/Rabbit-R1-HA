package com.github.itskenny0.r1ha.core.util

import android.app.Application

/**
 * Process-scoped toast helper. Initialised once from [com.github.itskenny0.r1ha.App.onCreate];
 * any code (including ViewModels and repositories) can then call into this object
 * without holding its own Context reference.
 *
 * **Two visibility tiers.** The user reported that even non-error toasts ("Session
 * refreshed", "Signed out") were showing up with toasts otherwise disabled, which was
 * the result of every call going through the force-show path. Now:
 *
 *  - [error] / [errorExpandable] — force-show via [R1Toast.userPush]. Use for failure
 *    feedback the user genuinely needs to see regardless of their toast preferences
 *    (HA call rejected, save failed, decrypt failed, etc.). Bypasses [R1Toast.enabled]
 *    and [R1Toast.minLevel].
 *  - [show] / [showExpandable] — gated INFO via [R1Toast.push]. Use for confirmations
 *    and progress chatter ("Backup restored", "Loaded N entities"). Only surfaces
 *    when the user has opted into the diagnostic feed at INFO or DEBUG in Settings.
 *
 * **Why no Android Toast.** The R1's 240×320 display truncates the OS Toast at ~28
 * chars mid-sentence — failure messages like "Validation error: Entity
 * media_player.foo doesn't support media_next_track" were getting clipped before the
 * user could read what went wrong. Every Toaster call routes through R1Toast which
 * renders an expandable in-app toast (tap for full text) via [ui.components.ToastHost]
 * mounted at the activity root. We keep the [init] entry point for back-compat with
 * the App.onCreate wiring even though we no longer need the Application reference.
 */
internal object Toaster {
    @Volatile private var app: Application? = null

    fun init(application: Application) { app = application }

    /**
     * Gated info toast. Same text for the inline preview + expanded body. Only
     * surfaces when [R1Toast.enabled] is true AND [R1Toast.minLevel] is at or below
     * INFO — i.e. the user has explicitly opted into the diagnostic toast feed. The
     * [long] parameter is kept for source-compat with the old API; it's ignored
     * because R1Toast picks its own visible duration based on level.
     */
    fun show(message: String, @Suppress("UNUSED_PARAMETER") long: Boolean = false) {
        R1Toast.push(R1Toast.Level.INFO, "ui", message, message)
    }

    /**
     * Gated info toast with distinct inline + expanded text. Same visibility rules
     * as [show]; the expanded body can be arbitrarily long (the host renders it on
     * tap). Useful for progress confirmations that have extra detail worth seeing
     * when verbose toasts are on.
     */
    fun showExpandable(shortText: String, fullText: String) {
        R1Toast.push(R1Toast.Level.INFO, "ui", shortText, fullText)
    }

    /**
     * Force-show error toast. Bypasses the user's R1Toast settings — use for failure
     * feedback the user genuinely needs to see (HA call rejected, settings save
     * failed, token decrypt failed). Rendered with R1.StatusRed accent in the host.
     */
    fun error(message: String) {
        R1Toast.userPush(R1Toast.Level.ERROR, "ui", message, message)
    }

    /**
     * Force-show error toast with distinct inline + expanded text. Same visibility
     * as [error]; use this when the failure has a long underlying message worth
     * reading in full (HA validation errors, exception messages).
     */
    fun errorExpandable(shortText: String, fullText: String) {
        R1Toast.userPush(R1Toast.Level.ERROR, "ui", shortText, fullText)
    }
}
