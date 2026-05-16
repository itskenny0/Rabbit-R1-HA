package com.github.itskenny0.r1ha.ui.components

import android.content.Context
import android.os.VibrationAttributes
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
 * Haptic helper that routes through whatever path the host device
 * actually honours. Different Android ROMs gate different APIs:
 *
 *  - **R1 stock LineageOS / CipherOS** — both `Vibrator` and
 *    `performHapticFeedback` work; latter was the original path.
 *  - **Xiaomi MIUI** — `performHapticFeedback` is silenced unless the
 *    user manually flips "Haptic feedback when typing" on; Vibrator
 *    is the only reliable route.
 *  - **Other LineageOS / vendor ROMs** — sometimes the inverse:
 *    Vibrator one-shots get filtered out unless they carry a
 *    [VibrationAttributes] with `USAGE_TOUCH`, while
 *    `performHapticFeedback` is unaffected.
 *
 * So we fire *both* paths and accept that a few well-tuned devices
 * will perceive each tap as a very slightly punchier click — that's
 * a much better failure mode than "nothing happens" on a $300 phone
 * because the ROM blessed the wrong API. The Vibrator call carries
 * an explicit `USAGE_TOUCH` attribute so it honours the system-level
 * Touch-feedback toggle exactly the way the LineageOS launcher does.
 *
 * `VIBRATE` permission is already declared in the manifest; minSdk 33
 * guarantees both [VibratorManager] and [VibrationAttributes].
 */
class R1Haptic internal constructor(
    private val vibrator: Vibrator?,
) {

    /** Short "click" feedback — wheel detents, button taps, scroll
     *  pips. Calibrated to feel like a single satisfying click rather
     *  than a buzz. */
    fun tick(view: View) = fire(view, tick = true)

    /** Heavier "you held that down" feedback — long-press menus,
     *  destructive-action confirmations, drag handles. Distinctly
     *  meatier than [tick] so the user can feel the difference between
     *  a tap registering and a hold registering. */
    fun longPress(view: View) = fire(view, tick = false)

    /** Shared core. Fires both the Vibrator (with USAGE_TOUCH so the
     *  system honours the touch-feedback setting consistently with the
     *  launcher) AND the View's performHapticFeedback so whichever the
     *  host ROM actually wires up produces tactile output. */
    private fun fire(view: View, tick: Boolean) {
        // 1) Vibrator path. We try the predefined effect first because
        //    on capable devices it gives a much nicer feel than a raw
        //    one-shot, then fall back to a one-shot at a duration
        //    that's long enough to reliably activate ERM motors (some
        //    older LineageOS phones drop sub-15 ms pulses entirely)
        //    while still being short enough to read as a click on LRAs.
        runCatching {
            val v = vibrator ?: return@runCatching
            if (!v.hasVibrator()) return@runCatching
            // Always try the predefined effect — its support-detection
            // is unreliable on enough ROMs that the check itself was
            // skipping output on capable hardware. When the effect
            // isn't actually supported, the system falls back to its
            // own default vibration; only when that fails do we hit
            // the catch and use our explicit one-shot below.
            val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
            val predefined = if (tick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
            val supported = v.areEffectsSupported(predefined).firstOrNull() ==
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES
            val effect = if (supported) {
                VibrationEffect.createPredefined(predefined)
            } else if (tick) {
                // 20 ms full-amplitude — reliably perceived as a single
                // click on every common motor type. The earlier 12 ms /
                // half-amplitude pulse was below the activation
                // threshold on some LineageOS-on-mid-range-phone setups
                // and the user got nothing.
                VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                // 35 ms full-amplitude for the heavier long-press. Long
                // enough to clearly read as "different from a tap".
                VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            v.vibrate(effect, attrs)
        }.onFailure {
            R1Log.w("R1Haptic", "vibrator path failed: ${it.message}")
        }

        // 2) View.performHapticFeedback path. Cheap; on a ROM that
        //    routes it to the same motor the Vibrator just hit, the
        //    system typically deduplicates within its scheduling
        //    window. On a ROM that silently drops the Vibrator path
        //    (some vendor builds), this is what actually fires. The
        //    @Suppress is required because CLOCK_TICK was deprecated in
        //    API 34 in favour of CONTEXT_CLICK; we accept both since
        //    different ROMs honour different constants.
        runCatching {
            @Suppress("DEPRECATION")
            val constant = if (tick) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(constant)
        }.onFailure {
            R1Log.d("R1Haptic", "performHapticFeedback failed: ${it.message}")
        }
    }

    companion object {
        /** Build an [R1Haptic] from a [Context]. Resolves the
         *  [VibratorManager] once; minSdk 33 guarantees it's
         *  available. */
        fun from(context: Context): R1Haptic {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            return R1Haptic(mgr?.defaultVibrator)
        }
    }
}

/** Composable accessor — caches an [R1Haptic] for the lifetime of the
 *  current composition. ReadOnlyComposable form for sites that only
 *  read the haptic once. */
@Composable
@ReadOnlyComposable
fun rememberHaptic(): R1Haptic = R1Haptic.from(LocalContext.current)

/** Stateful variant — use from regular composables. Caches the haptic
 *  so we don't re-fetch the VibratorManager on every recomposition. */
@Composable
fun rememberR1Haptic(): R1Haptic {
    val context = LocalContext.current
    return remember(context) { R1Haptic.from(context) }
}

/** Convenience — combines [rememberR1Haptic] with [LocalView] so a call
 *  site only needs one helper invocation. Returns a lambda that fires a
 *  tick when invoked. */
@Composable
fun rememberTickHaptic(): () -> Unit {
    val haptic = rememberR1Haptic()
    val view = LocalView.current
    return remember(haptic, view) { { haptic.tick(view) } }
}

/** Heavier long-press equivalent of [rememberTickHaptic]. */
@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptic = rememberR1Haptic()
    val view = LocalView.current
    return remember(haptic, view) { { haptic.longPress(view) } }
}
