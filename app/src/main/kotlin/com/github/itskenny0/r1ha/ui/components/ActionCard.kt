package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.coroutines.delay

/**
 * Card variant for stateless action entities — scenes, scripts, buttons. No on/off state,
 * no scalar to drive; the whole card is "fire the trigger". Layout mirrors SwitchCard's
 * header (DOMAIN · AREA, friendly name) so the deck reads cohesively, but the bottom
 * occupies a large ACTIVATE button instead of an on/off switch track.
 *
 * Pressing the button briefly flashes the label to "FIRED" + the button to a slightly
 * brighter accent so the user gets visible confirmation that their tap registered — HA's
 * state_changed event for these entities is just a timestamp bump and doesn't really
 * surface back through the existing reconciliation path, so we synthesise the feedback
 * locally.
 */
@Composable
fun ActionCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    onFire: () -> Unit,
    overscrollProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    // Local-feedback flash: when the user taps, hold "FIRED" for ~700 ms then return to
    // ACTIVATE. Pure UI state — the actual service-call goes through the same haRepository
    // path as everything else and gets the failure-toast/rollback treatment automatically.
    var fired by remember { mutableStateOf(false) }
    LaunchedEffect(fired) {
        if (fired) {
            delay(700L)
            fired = false
        }
    }

    // Smooth the externally-driven overscroll value so it feels like a build-up rather
    // than the discrete per-detent jumps the wheel produces. Spring stays under critical
    // so the bar settles, doesn't bounce around.
    val animatedOverscroll by androidx.compose.animation.core.animateFloatAsState(
        targetValue = overscrollProgress.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        ),
        label = "action-overscroll",
    )

    val buttonColor by animateColorAsState(
        targetValue = if (fired) accent else accent.copy(alpha = 0.88f),
        label = "action-btn-color",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(R1.Bg)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        // ── Overscroll bar — fills as the user wheels up at the top of the deck.
        // Visible only when there's any progress; full-width hairline track + accent fill
        // that grows from the left. Pairs with a hint label "▲ HOLD WHEEL TO FIRE" so a
        // first-time user understands the gesture.
        if (animatedOverscroll > 0.001f) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(R1.Hairline),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedOverscroll)
                        .height(3.dp)
                        .background(accent),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (animatedOverscroll >= 0.999f) "▲ FIRING" else "▲ KEEP WHEELING UP TO FIRE",
                style = R1.labelMicro,
                color = accent.copy(alpha = (0.5f + animatedOverscroll * 0.5f).coerceIn(0f, 1f)),
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── Header ─────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(domainLabel, style = R1.labelMicro, color = R1.Ink)
            Spacer(Modifier.width(8.dp))
            Text("· TRIGGER", style = R1.labelMicro, color = R1.InkMuted)
            if (showArea && !state.area.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.area.replace('_', ' ').uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.friendlyName,
            style = R1.titleCard,
            color = R1.Ink,
            maxLines = 2,
        )

        Spacer(Modifier.weight(1f))

        // ── Big ACTIVATE button ─────────────────────────────────────────────────────
        // Full-width 72dp tile, accent fill, monospace label centred. r1Pressable gives
        // the scale dip + haptic; the local `fired` flag swaps the label to FIRED for a
        // beat so the user sees their press registered.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(R1.ShapeM)
                .background(buttonColor)
                .r1Pressable(
                    onClick = {
                        fired = true
                        onFire()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // ▶ glyph drawn in monospace for visual weight; "FIRED"/"ACTIVATE" label
                // swaps via the local fired state.
                Text(
                    text = if (fired) "● FIRED" else "▶ ACTIVATE",
                    style = R1.numeralM,
                    color = R1.Bg,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Secondary hint — clarifies that this is a one-shot trigger, not a toggle, so
        // users who hit it accidentally know there's no "undo" by tapping again.
        Text(
            text = if (state.isOn) "RUNNING…" else "TAP TO FIRE — NO TOGGLE",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}
