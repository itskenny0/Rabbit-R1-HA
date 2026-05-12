package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Read-only card for sensor / binary_sensor entities — temperature probes, humidity,
 * energy meters, motion detectors, leak alarms, door contacts. No wheel input, no tap
 * action (a tap is silently ignored at the EntityCard wrapper level for sensor domains).
 *
 * For plain sensors the body is a big monospace numeric readout taken straight from the
 * raw HA state (HA already sends a presentation-ready string like "21.7" — no need to
 * coerce it through Number-formatting and lose precision/locale handling). The unit
 * suffix sits inline next to the value. For binary_sensors there's no number; the readout
 * is the state word itself (CLOSED, OPEN, MOTION, CLEAR, etc.) coloured by accent vs
 * muted depending on isOn.
 */
@Composable
fun SensorCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    modifier: Modifier = Modifier,
) {
    val isBinary = state.id.domain == Domain.BINARY_SENSOR
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
            if (!state.deviceClass.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.deviceClass.uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
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

        // ── Body: big readout ──────────────────────────────────────────────────────
        if (isBinary) {
            // Binary sensors — render the state word itself (sized like our numeric
            // readouts so the visual weight matches a temperature display). We map a few
            // common device_class values to friendlier words; everything else falls back
            // to the raw state text uppercased.
            val word = friendlyBinaryWord(state)
            Text(
                text = word,
                style = R1.numeralXl,
                color = if (state.isOn) accent else R1.InkSoft,
            )
        } else {
            // Plain sensors — render the rawState as the body. Use a Row so the unit
            // suffix sits inline with the bottom of the digits like the "%" suffix on
            // scalar cards. Bigger negative letter-spacing on long readings (>4 chars,
            // e.g. "1234.5") keeps them on one line on the 240 px display.
            val value = state.rawState ?: "—"
            val tightenForLength = if (value.length >= 4) R1.numeralXl.copy(letterSpacing = (-3).sp)
            else R1.numeralXl
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.wrapContentSize()) {
                Text(text = value, style = tightenForLength, color = R1.Ink)
                if (!state.unit.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.unit,
                        style = R1.numeralM,
                        color = accent,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Footer hint — clarifies the read-only nature so users don't expect the
        // wheel to do anything here. Stays subtle (labelMicro on InkMuted).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "READ-ONLY",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

/**
 * Map a binary sensor's [state.rawState] to a presentation word. The HA convention is
 * "on" / "off", but the natural word depends on `device_class` — a door is OPEN/CLOSED,
 * a motion sensor is MOTION/CLEAR, a leak sensor is LEAK/DRY, etc. Falls back to a
 * straight TRUE/FALSE for anything we don't recognise, which still reads better than the
 * raw "on" / "off" everywhere.
 */
private fun friendlyBinaryWord(state: EntityState): String {
    val on = state.isOn
    return when (state.deviceClass) {
        "door", "garage_door", "window", "opening" -> if (on) "OPEN" else "CLOSED"
        "motion", "occupancy", "presence" -> if (on) "MOTION" else "CLEAR"
        "moisture" -> if (on) "LEAK" else "DRY"
        "smoke" -> if (on) "SMOKE" else "CLEAR"
        "gas", "carbon_monoxide" -> if (on) "DETECTED" else "CLEAR"
        "lock" -> if (on) "UNLOCKED" else "LOCKED"
        "battery" -> if (on) "LOW" else "OK"
        "power", "plug" -> if (on) "POWER" else "OFF"
        "connectivity" -> if (on) "ONLINE" else "OFFLINE"
        else -> if (on) "ON" else "OFF"
    }
}

