package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The "switch" card variant — rendered for entities that have no settable scalar (a switch
 * dressed as a light, a non-dimmable bulb, a media player without VOLUME_SET, etc). Looks
 * like a physical two-position toggle: a tall track with ON at top and OFF at bottom, and a
 * thumb that snaps between them. The wheel and a tap both flip it; the slider position
 * animates with the same snappy spring as the percent slider.
 *
 * Layout mirrors the percent card's spec (DOMAIN · AREA header, friendly-name title, label
 * micros) so the cards in the stack feel cohesive — only the value display differs.
 */
@Composable
fun SwitchCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    onTapToggle: () -> Unit,
    onSetOn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(R1.Bg)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
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
            Text("· ON/OFF", style = R1.labelMicro, color = R1.InkMuted)
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
        Spacer(Modifier.height(20.dp))

        // ── State word ─────────────────────────────────────────────────────────────
        // Crossfade ON↔OFF so the flip reads as a deliberate state change rather than a
        // text swap. AnimatedContent picks the labels by isOn key, fades the outgoing
        // word out while the new one fades in — quicker than the default 220 ms because
        // the user has already seen the thumb move on the switch track and the readout
        // should land before the eye drifts away.
        val labelColor by androidx.compose.animation.animateColorAsState(
            targetValue = if (state.isOn) accent else R1.InkSoft,
            label = "switch-label-color",
        )
        // State word — domain-aware. Covers in motion show OPENING/CLOSING (not ON/OFF
        // which would lie about the in-between state); vacuums show CLEANING/RETURNING/
        // DOCKED/IDLE for the same reason. Everything else falls back to ON/OFF.
        val stateWord = friendlySwitchStateWord(state)
        androidx.compose.animation.AnimatedContent(
            targetState = stateWord,
            transitionSpec = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(durationMillis = 120),
                ) togetherWith androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(durationMillis = 120),
                )
            },
            label = "switch-state-word",
        ) { word ->
            Text(
                text = word,
                style = R1.numeralXl,
                color = labelColor,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── The two-position switch ────────────────────────────────────────────────
        SwitchTrack(
            isOn = state.isOn,
            accent = accent,
            onSetOn = onSetOn,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Vertical track 12dp wide × ~120dp tall, with two end-stop labels and a thumb that animates
 * between the top (ON) and bottom (OFF). The ON / OFF labels are clickable: tap ON to set
 * the entity on regardless of current state, tap OFF to set off. The track itself isn't a
 * drag-handle — driving the switch is wheel-or-tap-on-labels only.
 */
@Composable
private fun SwitchTrack(
    isOn: Boolean,
    accent: Color,
    onSetOn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Critically damped spring — a physical toggle snaps to its stop and stays put; no
    // bounce. (The percent slider on the other card variant does bounce because there are
    // intermediate positions to telegraph; here there are only two stops.) Critical damping
    // also keeps the animated value strictly inside [0, 1], which matters because the thumb
    // is placed by multiplying this fraction into a Dp — a brief overshoot to -0.03 used to
    // produce a negative Dp and crash `Modifier.padding(top = …)`. We now use `offset`
    // instead, which accepts any Dp, but the critically-damped spring is the correct shape
    // for this control regardless.
    val rawFrac by animateFloatAsState(
        targetValue = if (isOn) 0f else 1f,  // 0 = top (ON), 1 = bottom (OFF)
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "switch-frac",
    )
    val frac = rawFrac.coerceIn(0f, 1f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left labels — ON top, OFF bottom. Both are clickable explicit setters.
        Column(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "ON",
                style = R1.numeralM,
                color = if (isOn) accent else R1.InkMuted,
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = { onSetOn(true) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                text = "OFF",
                style = R1.numeralM,
                color = if (!isOn) R1.InkSoft else R1.InkMuted,
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = { onSetOn(false) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(16.dp))

        // Track + thumb.
        BoxWithConstraints(
            modifier = Modifier
                .height(120.dp)
                .width(12.dp),
        ) {
            // Track.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .align(Alignment.Center)
                    .background(R1.Hairline),
            )
            // Thumb — 24×8 dp pill, accent when ON, dim when OFF.
            val thumbHeight = 8.dp
            val trackHeight = maxHeight
            val travel = trackHeight - thumbHeight
            // `offset` rather than `padding` for the Y position — `padding` rejects negative
            // Dp with IllegalArgumentException, and any non-zero spring bounce would briefly
            // produce a value just outside [0, 1]. `offset` accepts any Dp.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = travel * frac)
                    .width(24.dp)
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOn) accent else R1.InkSoft),
            )
        }
    }
}

/**
 * Domain-aware state word for the SwitchCard's big readout. Covers in motion read
 * OPENING / CLOSING; vacuums read CLEANING / DOCKED / RETURNING / PAUSED; locks read
 * UNLOCKED / LOCKED; climate reads its HVAC mode (HEAT / COOL / etc.) when on. Falls
 * back to ON / OFF for everything else.
 */
private fun friendlySwitchStateWord(state: EntityState): String {
    val raw = state.rawState?.lowercase() ?: return if (state.isOn) "ON" else "OFF"
    return when (state.id.domain) {
        com.github.itskenny0.r1ha.core.ha.Domain.COVER,
        com.github.itskenny0.r1ha.core.ha.Domain.VALVE -> when (raw) {
            "open" -> "OPEN"
            "closed" -> "CLOSED"
            "opening" -> "OPENING"
            "closing" -> "CLOSING"
            "stopped" -> "STOPPED"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.VACUUM -> when (raw) {
            "cleaning" -> "CLEANING"
            "docked" -> "DOCKED"
            "returning" -> "RETURNING"
            "paused" -> "PAUSED"
            "idle" -> "IDLE"
            "error" -> "ERROR"
            else -> raw.uppercase()
        }
        com.github.itskenny0.r1ha.core.ha.Domain.LOCK -> if (state.isOn) "UNLOCKED" else "LOCKED"
        com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE,
        com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER -> raw.uppercase()
        com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER -> when (raw) {
            "playing" -> "PLAYING"
            "paused" -> "PAUSED"
            "idle" -> "IDLE"
            "standby" -> "STANDBY"
            "buffering" -> "BUFFERING"
            else -> raw.uppercase()
        }
        else -> if (state.isOn) "ON" else "OFF"
    }
}
