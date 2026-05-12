package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * "Mission Control" — the default theme. Heavy orange on near-black, monospace numerals,
 * uppercase letter-spaced labels, and a horizontal tape-meter slider directly under the
 * percent readout (rather than running the full screen height on the right edge). The whole
 * card reads like an instrument panel: SOURCE on top, value in the centre, status pill below.
 */
object PragmaticHybridTheme : R1Theme {
    override val id = ThemeId.PRAGMATIC_HYBRID
    override val displayName = "Pragmatic Hybrid"
    override val systemBars = SystemBarColors(status = R1.Bg, nav = R1.Bg)
    override val baseline = sharedDarkBaseline

    private fun accentColor(role: CardRenderModel.AccentRole) = when (role) {
        CardRenderModel.AccentRole.WARM -> R1.AccentWarm
        CardRenderModel.AccentRole.COOL -> R1.AccentCool
        CardRenderModel.AccentRole.GREEN -> R1.AccentGreen
        CardRenderModel.AccentRole.NEUTRAL -> R1.AccentNeutral
    }

    private fun domainLabel(glyph: CardRenderModel.Glyph): String = when (glyph) {
        CardRenderModel.Glyph.LIGHT -> "LIGHT"
        CardRenderModel.Glyph.FAN -> "FAN"
        CardRenderModel.Glyph.COVER -> "COVER"
        CardRenderModel.Glyph.MEDIA_PLAYER -> "MEDIA"
        CardRenderModel.Glyph.SWITCH -> "SWITCH"
        CardRenderModel.Glyph.LOCK -> "LOCK"
        CardRenderModel.Glyph.HUMIDIFIER -> "HUMIDIFIER"
        CardRenderModel.Glyph.CLIMATE -> "CLIMATE"
        CardRenderModel.Glyph.NUMBER -> "NUMBER"
        CardRenderModel.Glyph.VALVE -> "VALVE"
        CardRenderModel.Glyph.VACUUM -> "VACUUM"
        CardRenderModel.Glyph.WATER_HEATER -> "WATER HEATER"
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        // Per-card accent override (from EntityOverride.accentColor) takes precedence
        // over the domain-derived role colour. Lets users tint individual cards without
        // touching their HA setup.
        val accent = model.accentOverride ?: accentColor(model.accent)
        val ui = LocalUiOptions.current

        Row(
            modifier = modifier
                .fillMaxSize()
                .background(R1.Bg)
                .padding(start = 22.dp, top = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            // ── Main content column ─────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                DomainHeader(
                    domainLabel = domainLabel(model.domainGlyph),
                    area = model.area,
                    accent = accent,
                    showArea = ui.showAreaLabel,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    style = R1.titleCard,
                    color = R1.Ink,
                    maxLines = 2,
                )
                Spacer(Modifier.height(20.dp))
                BigReadout(
                    percent = model.percent,
                    showPercentSuffix = ui.displayMode == DisplayMode.PERCENT,
                    accent = accent,
                    // For entities that surface a domain-native value (climate "21 °C"),
                    // displayValue/displayUnit replace the percent number + "%" suffix.
                    overrideText = model.displayValue,
                    overrideUnit = model.displayUnit,
                    textScale = model.textScale,
                    lightEntityId = if (model.lightWheelMode != null) com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText) else null,
                    lightWheelMode = model.lightWheelMode,
                )
                // Light effect chip — only on lights that report a non-empty effect_list.
                // Tap → cycles through (None → first → … → last → None). Hidden on bulbs
                // that don't expose effects so it doesn't clutter the card.
                if (model.lightEffectListSize > 0) {
                    Spacer(Modifier.height(8.dp))
                    EffectChip(
                        entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                        currentEffect = model.lightEffect,
                        accent = accent,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (ui.showOnOffPill) OnOffPill(isOn = model.isOn, accent = accent)
            }

            // ── Vertical tape meter — inset from the right edge, ~200 dp tall ───────
            Spacer(Modifier.width(20.dp))
            VerticalTapeMeter(percent = model.percent, accent = accent)
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────

@Composable
private fun DomainHeader(
    domainLabel: String,
    area: String?,
    accent: Color,
    showArea: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Accent chip
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = domainLabel,
            style = R1.labelMicro,
            color = R1.Ink,
        )
        if (showArea && !area.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(text = "·", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.width(8.dp))
            Text(
                text = area.replace('_', ' ').uppercase(),
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/**
 * The percent readout — single Text with the live value, plus a brief glitch treatment while
 * the wheel is actively churning. No per-digit AnimatedContent (the 180 ms slot-machine slide
 * stacked badly under fast wheel input, making the value visibly lag the wheel by hundreds of
 * ms). The glitch comes from (a) a small Y wobble on the whole readout, and (b) two ghost
 * copies in red and cyan offset left/right — both fade in only while changing.
 */
@Composable
internal fun BigReadout(
    percent: Int,
    showPercentSuffix: Boolean,
    accent: Color,
    overrideText: String? = null,
    overrideUnit: String? = null,
    textScale: Float = 1.0f,
    lightEntityId: com.github.itskenny0.r1ha.core.ha.EntityId? = null,
    lightWheelMode: com.github.itskenny0.r1ha.core.ha.LightWheelMode? = null,
) {
    // Plain, snappy readout. Jitter and chromatic aberration were obscuring the live value
    // and chewing recomposition budget; the slider, the spring on the slider, and the
    // haptic on each detent already telegraph the wheel motion. We can layer subtler
    // effects back in once the core feel is solid.
    //
    // When overrideText is non-null (climate "21" with overrideUnit "°C") it replaces
    // both the percent number AND the percent suffix — domain-native readings are more
    // useful than a meaningless 60% on a thermostat.
    val bodyText = overrideText ?: percent.coerceIn(0, 100).toString()
    val suffixText = overrideUnit ?: if (showPercentSuffix) "%" else null
    // Scale numeralXl (and the suffix proportionally) by the per-card text-scale factor.
    // Bypassing the natural sp scaling so the readout obeys both the user's font size *and*
    // the per-card override.
    val numeralStyle = R1.numeralXl.copy(
        fontSize = R1.numeralXl.fontSize * textScale,
        lineHeight = R1.numeralXl.lineHeight * textScale,
    )
    val suffixStyle = R1.numeralM.copy(fontSize = R1.numeralM.fontSize * textScale)
    // Wrap the readout in a column so we can stack the optional "MODE: CT" chip below
    // for light cards. Tap on the readout cycles the wheel mode when one is offered;
    // non-light cards (or single-mode lights) don't get the gesture.
    val onCycle = com.github.itskenny0.r1ha.core.theme.LocalOnCycleLightMode.current
    val cycleClickable: Modifier = if (lightEntityId != null && lightWheelMode != null && onCycle != null) {
        Modifier.r1Pressable(onClick = { onCycle(lightEntityId) })
    } else Modifier
    Column(modifier = Modifier.wrapContentSize()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = cycleClickable,
        ) {
            Text(
                text = bodyText,
                style = numeralStyle,
                color = R1.Ink,
            )
            if (suffixText != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = suffixText,
                    style = suffixStyle,
                    color = accent,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
        }
        // Mode chip — only shown when there's a non-default mode OR a tap-to-cycle is
        // available. Helps the user discover the cycle gesture (subtle text "TAP TO
        // CYCLE" hint) and confirms what the wheel will drive.
        if (lightWheelMode != null && onCycle != null && lightEntityId != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (lightWheelMode) {
                        com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> "BRIGHTNESS"
                        com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> "COLOUR TEMP"
                        com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> "HUE"
                    },
                    style = R1.labelMicro,
                    color = accent,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "· TAP READOUT TO CYCLE",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
}

/**
 * Vertical tape meter, inset from the right edge of the card. Track is a 2 dp hairline; fill
 * grows from the bottom and ends in a 4 dp accent-coloured thumb at the current value.
 * [rememberSliderFraction] gives the fill a snappy bouncy spring — each detent visibly
 * "jumps" past the target and settles, which reads as mechanical feedback per click.
 */
@Composable
internal fun VerticalTapeMeter(percent: Int, accent: Color) {
    val fraction = rememberSliderFraction(percent).coerceIn(0f, 1f)
    // Tick row labels — at fixed Y positions, monospace tiny text on the inside edge.
    Row(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 0/25/50/75/100 labels — vertically distributed alongside the track.
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            listOf("100", "75", "50", "25", "0").forEach { tick ->
                Text(text = tick, style = R1.numeralS, color = R1.InkMuted)
            }
        }
        Spacer(Modifier.width(6.dp))
        // Track + fill + thumb. Anchor to BottomCenter so the fill grows upward.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(12.dp),
        ) {
            // Hairline track.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .align(Alignment.Center)
                    .background(R1.SurfaceMuted),
            )
            // Fill — grows from the bottom up to `fraction` of available height.
            Box(
                modifier = Modifier
                    .fillMaxHeight(fraction)
                    .width(4.dp)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            // Thumb — a 12 dp wide capsule sitting at the top of the fill.
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Spacer(Modifier.fillMaxHeight(fraction))
            }
            // Thumb capsule — positioned by chaining a Spacer that pushes it up by `fraction`.
            ThumbCapsule(fraction = fraction, accent = accent)
        }
    }
}

@Composable
private fun ThumbCapsule(fraction: Float, accent: Color) {
    // BoxWithConstraints lets us compute the absolute thumb Y from `fraction` cheaply —
    // recomposes only when `fraction` does (post-spring settle).
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxHeight(),
    ) {
        val trackH = maxHeight
        val thumbH = 6.dp
        val travel = trackH - thumbH
        // fraction = 1.0 → thumb at the top; fraction = 0.0 → thumb at the bottom.
        // `offset` (not `padding`) because the slider's spring overshoots — a fraction of
        // 1.05 briefly produces a negative `offsetFromTop`, and `padding` crashes on
        // negative Dp. `offset` accepts any Dp, so a tiny visible overshoot is fine.
        val offsetFromTop = travel * (1f - fraction)
        Box(
            modifier = Modifier
                .offset(y = offsetFromTop)
                .width(12.dp)
                .height(thumbH)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}

/**
 * Tap-to-cycle chip for light effects. Shows "EFFECT: NAME" when an effect is active,
 * or "EFFECT: NONE — TAP TO CYCLE" when none is set. Tap fires the LocalOnCycleLightEffect
 * callback which the card-stack screen wires to vm.cycleLightEffect. Stays inert when
 * the callback is unavailable (e.g. previews where there's no VM in scope).
 */
@Composable
private fun EffectChip(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    currentEffect: String?,
    accent: Color,
) {
    val onCycle = com.github.itskenny0.r1ha.core.theme.LocalOnCycleLightEffect.current
    val active = !currentEffect.isNullOrBlank()
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (active) accent else R1.SurfaceMuted)
            .let { m ->
                if (onCycle != null) m.r1Pressable(onClick = { onCycle(entityId) }) else m
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = if (active) "FX · ${currentEffect!!.uppercase()}" else "FX · NONE",
            style = R1.labelMicro,
            color = if (active) R1.Bg else R1.InkSoft,
        )
    }
}

@Composable
private fun OnOffPill(isOn: Boolean, accent: Color) {
    val (label, fg, bg) = if (isOn) {
        Triple("● ON", R1.Bg, accent)
    } else {
        Triple("○ OFF", R1.InkSoft, R1.SurfaceMuted)
    }
    Box(
        modifier = Modifier
            .clip(R1.ShapeM)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = fg)
    }
}
