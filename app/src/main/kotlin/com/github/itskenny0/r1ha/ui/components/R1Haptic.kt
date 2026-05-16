package com.github.itskenny0.r1ha.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.github.itskenny0.r1ha.core.util.R1Log

/**
 * Direct-to-vibrator haptic helper. Most of the app's tactile feedback
 * historically went through [View.performHapticFeedback] with
 * [HapticFeedbackConstants.CLOCK_TICK], which works reliably on the R1's
 * stock LineageOS / CipherOS ROM but is gated to near-silence on many
 * vendor ROMs — Xiaomi MIUI in particular has the touch-feedback
 * channel disabled by default and ignores `performHapticFeedback` calls
 * unless the user manually turns "Haptic feedback when typing" on under
 * Settings → Sound → Vibration.
 *
 * This helper routes through [Vibrator] directly: predefined effects
 * (`EFFECT_TICK` / `EFFECT_CLICK`) when the device advertises support,
 * one-shot pulses otherwise, and finally `performHapticFeedback` as a
 * last-resort fallback on devices without a vibrator. The `VIBRATE`
 * permission is already declared in the manifest.
 *
 * The vibrator service is queried once per host context via the
 * [rememberHaptic] composable and cached for the rest of the screen's
 * composition — `VibratorManager.defaultVibrator` is cheap to fetch but
 * we don't need to re-grab it on every tick.
 */
class R1Haptic internal constructor(
    private val vibrator: Vibrator?,
    private val supportsPredefinedTick: Boolean,
    private val supportsPredefinedClick: Boolean,
) {
    /** Short "click" / "detent passed" feedback — wheel ticks, button
     *  taps, scroll-position pips. Calibrated to feel like a single
     *  satisfying click rather than a buzz. */
    fun tick(view: View) {
        runCatching {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                val effect = if (supportsPredefinedTick) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                } else {
                    // Soft one-shot — 12 ms is short enough to read as a
                    // tick rather than a buzz; AMPLITUDE/2 keeps it
                    // subtle enough not to compete with whatever the
                    // user is doing (a fast wheel spin would otherwise
                    // be unpleasant at full amplitude).
                    VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE / 2)
                }
                v.vibrate(effect)
                return
            }
            // No vibrator on the host — fall back to performHapticFeedback
            // so devices that DO route CLOCK_TICK through the soft-keyboard
            // haptic channel still feel something.
            @Suppress("DEPRECATION")
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }.onFailure {
            R1Log.d("R1Haptic", "tick failed: ${it.message}")
        }
    }

    /** Heavier "you held that down" feedback — long-press
     *  context-menu, destructive action confirmations, drag-handle
     *  pickup. Distinctly meatier than [tick] so the user can
     *  feel the difference between a tap and a hold registering. */
    fun longPress(view: View) {
        runCatching {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                val effect = if (supportsPredefinedClick) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    // 28 ms one-shot at full amplitude — feels like a
                    // firm thump, distinct from the 12 ms tick above.
                    VibrationEffect.createOneShot(28L, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                v.vibrate(effect)
                return
            }
            @Suppress("DEPRECATION")
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }.onFailure {
            R1Log.d("R1Haptic", "longPress failed: ${it.message}")
        }
    }

    companion object {
        /** Build an [R1Haptic] from a [Context]. Resolves the
         *  [VibratorManager] (minSdk 33 guarantees it's available) and
         *  probes the device's effect-support table once. */
        fun from(context: Context): R1Haptic {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = mgr?.defaultVibrator
            // areEffectsSupported returns SUPPORTED / NOT_SUPPORTED /
            // UNKNOWN per effect — only treat SUPPORTED as a hit. For
            // UNKNOWN we fall through to the one-shot path which is
            // universally implementable, so we get a tick either way.
            val supportsTick: Boolean
            val supportsClick: Boolean
            if (vibrator == null) {
                supportsTick = false
                supportsClick = false
            } else {
                val results = runCatching {
                    vibrator.areEffectsSupported(
                        VibrationEffect.EFFECT_TICK,
                        VibrationEffect.EFFECT_CLICK,
                    )
                }.getOrNull()
                supportsTick = results?.getOrNull(0) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                supportsClick = results?.getOrNull(1) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
            }
            return R1Haptic(vibrator, supportsTick, supportsClick)
        }
    }
}

/** Composable accessor — caches an [R1Haptic] for the lifetime of the
 *  current composition. Each pressable widget calls this once and uses
 *  the result for every tap/long-press during its lifetime. */
@Composable
@ReadOnlyComposable
fun rememberHaptic(): R1Haptic {
    // ReadOnlyComposable is fine here because we're only reading the
    // composition-local LocalContext — no state writes — and the
    // `remember`-equivalent caching happens inside the actual composable
    // call sites that need it (each calls rememberR1Haptic which uses
    // remember; this overload is for ReadOnlyComposable sites only).
    return R1Haptic.from(LocalContext.current)
}

/** Stateful variant — use this from regular composables. Caches the
 *  haptic so we don't re-probe the vibrator's effect-support table on
 *  every recomposition. */
@Composable
fun rememberR1Haptic(): R1Haptic {
    val context = LocalContext.current
    return remember(context) { R1Haptic.from(context) }
}

/** Convenience — combines [rememberR1Haptic] with [LocalView] so a
 *  call site only needs a single helper invocation. Returns a lambda
 *  that fires a tick haptic when invoked. */
@Composable
fun rememberTickHaptic(): () -> Unit {
    val haptic = rememberR1Haptic()
    val view = LocalView.current
    return remember(haptic, view) { { haptic.tick(view) } }
}

/** Same shape as [rememberTickHaptic] but fires the heavier long-press
 *  effect. Use from long-press handlers and destructive-confirm sites. */
@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptic = rememberR1Haptic()
    val view = LocalView.current
    return remember(haptic, view) { { haptic.longPress(view) } }
}
