package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
        Text(
            text = if (state.isOn) "ON" else "OFF",
            style = R1.numeralXl,
            color = if (state.isOn) accent else R1.InkSoft,
        )

        Spacer(Modifier.height(14.dp))

        // ── The two-position switch ────────────────────────────────────────────────
        // Vertical track, ON marker at top, OFF marker at bottom, thumb that snaps between.
        SwitchTrack(isOn = state.isOn, accent = accent, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Vertical track 12dp wide × ~120dp tall, with two end-stop labels and a thumb that animates
 * between the top (ON) and bottom (OFF). Sits centred horizontally inside its [modifier]
 * width. The visual is deliberately big — this is the primary affordance on the switch card.
 */
@Composable
private fun SwitchTrack(
    isOn: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val frac by animateFloatAsState(
        targetValue = if (isOn) 0f else 1f,  // 0 = top (ON), 1 = bottom (OFF)
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "switch-frac",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left labels — ON top, OFF bottom.
        Column(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "ON",
                style = R1.labelMicro,
                color = if (isOn) accent else R1.InkMuted,
            )
            Text(
                text = "OFF",
                style = R1.labelMicro,
                color = if (!isOn) R1.InkSoft else R1.InkMuted,
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
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = travel * frac)
                    .width(24.dp)
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isOn) accent else R1.InkSoft),
            )
        }
    }
}
