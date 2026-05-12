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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
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
                    textSizeSp = model.textSizeSp,
                    lightEntityId = if (model.lightWheelMode != null) com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText) else null,
                    lightWheelMode = model.lightWheelMode,
                )
                // Light controls — segmented mode buttons (BRIGHT / WHITE / COLOUR) +
                // an EFFECTS button. The mode buttons surface only the modes the bulb
                // actually supports; the EFFECTS button is hidden when effect_list is
                // empty. Tapping a mode button switches the wheel target immediately;
                // tapping EFFECTS opens the effect picker overlay (rendered at screen
                // scope from CardStackScreen — see LightControlsRow's implementation).
                if (model.lightAvailableModes.size > 1 || model.lightEffectListSize > 0) {
                    Spacer(Modifier.height(8.dp))
                    LightControlsRow(
                        entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                        currentMode = model.lightWheelMode
                            ?: com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS,
                        availableModes = model.lightAvailableModes,
                        currentEffect = model.lightEffect,
                        effectList = model.lightEffectList,
                        accent = accent,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (ui.showOnOffPill) OnOffPill(isOn = model.isOn, accent = accent)
            }

            // ── Vertical tape meter — inset from the right edge, ~200 dp tall ───────
            Spacer(Modifier.width(20.dp))
            VerticalTapeMeter(
                percent = model.percent,
                accent = accent,
                tickLabels = model.meterLabels,
            )
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
    /** Absolute readout size in sp; null = use theme default (R1.numeralXl, 72 sp). */
    textSizeSp: Int? = null,
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
    // Apply the per-card absolute readout size. When textSizeSp is null, fall through to
    // the theme defaults (numeralXl 72 sp, numeralM 20 sp). When set, scale the suffix
    // (numeralM) proportionally so the unit doesn't dwarf the number — but floor it at
    // 8 sp so tiny readouts (6 sp for sensor headlines) don't make the suffix invisible.
    val defaultBodySp = EntityOverride.DEFAULT_TEXT_SIZE_SP
    val numeralStyle = if (textSizeSp != null) {
        R1.numeralXl.copy(
            fontSize = textSizeSp.sp,
            lineHeight = (textSizeSp * 1.05f).sp,
        )
    } else R1.numeralXl
    val suffixStyle = if (textSizeSp != null) {
        val proportionalSuffix = (textSizeSp.toFloat() / defaultBodySp.toFloat() *
            R1.numeralM.fontSize.value).coerceAtLeast(8f)
        R1.numeralM.copy(fontSize = proportionalSuffix.sp)
    } else R1.numeralM
    // Readout row only. The mode-cycle gesture moved off the readout (it was invisible
    // to users — see #117) and onto explicit segmented BRIGHT / WHITE / COLOUR buttons
    // rendered by [LightControlsRow] below the readout. lightEntityId / lightWheelMode
    // remain as params so themes still receive enough context for future visual hooks
    // (e.g. accent tint based on which mode is active).
    Row(verticalAlignment = Alignment.Bottom) {
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
}

/**
 * Vertical tape meter, inset from the right edge of the card. Track is a 2 dp hairline; fill
 * grows from the bottom and ends in a 4 dp accent-coloured thumb at the current value.
 * [rememberSliderFraction] gives the fill a snappy bouncy spring — each detent visibly
 * "jumps" past the target and settles, which reads as mechanical feedback per click.
 */
@Composable
internal fun VerticalTapeMeter(
    percent: Int,
    accent: Color,
    /** Top→bottom tick labels. Null = the default 0..100 percent labels. Climate /
     *  number cards pass their domain-native range so the meter reads in real units. */
    tickLabels: List<String>? = null,
) {
    val fraction = rememberSliderFraction(percent).coerceIn(0f, 1f)
    val labels = tickLabels ?: listOf("100", "75", "50", "25", "0")
    // Tick row labels — at fixed Y positions, monospace tiny text on the inside edge.
    Row(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tick labels — vertically distributed alongside the track. List is top→bottom.
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            labels.forEach { tick ->
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
 * Light-card controls — segmented mode buttons (BRIGHT / WHITE / COLOUR) and an
 * EFFECTS button. Replaces the previous tap-to-cycle gestures, which were invisible to
 * users (they only manifested as a near-invisible "TAP READOUT TO CYCLE" hint below
 * the readout). The segmented buttons are unmistakable affordances; tapping any of
 * them sets the wheel mode directly via [LocalOnSetLightWheelMode]. The EFFECTS button
 * opens a full-screen picker (see [EffectPickerSheet]) so the user sees every
 * available effect at once rather than blindly cycling.
 *
 * Modes are filtered against [availableModes] so a tunable-white bulb doesn't surface
 * a COLOUR button it can't honour. The EFFECTS button is hidden entirely when the
 * bulb's effect_list is empty.
 */
@Composable
internal fun LightControlsRow(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    currentMode: com.github.itskenny0.r1ha.core.ha.LightWheelMode,
    availableModes: List<com.github.itskenny0.r1ha.core.ha.LightWheelMode>,
    currentEffect: String?,
    effectList: List<String>,
    accent: Color,
) {
    val onSetMode = com.github.itskenny0.r1ha.core.theme.LocalOnSetLightWheelMode.current
    val onOpenPicker = com.github.itskenny0.r1ha.core.theme.LocalOnOpenEffectPicker.current
    val visibleModes = availableModes.ifEmpty {
        listOf(com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Segmented mode buttons — only render the ones the bulb supports. A single-
        // mode (brightness-only) bulb gets nothing because there's no choice to make,
        // matching the original cycle-chip's "size ≤ 1 = no-op" behaviour.
        if (visibleModes.size > 1) {
            visibleModes.forEachIndexed { idx, mode ->
                val active = mode == currentMode
                val label = when (mode) {
                    com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> "BRIGHT"
                    com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> "WHITE"
                    com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> "COLOUR"
                }
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(if (active) accent else R1.SurfaceMuted)
                        .let { m ->
                            if (onSetMode != null) m.r1Pressable(onClick = { onSetMode(entityId, mode) }) else m
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = label,
                        style = R1.labelMicro,
                        color = if (active) R1.Bg else R1.InkSoft,
                    )
                }
                if (idx < visibleModes.lastIndex) Spacer(Modifier.width(4.dp))
            }
        }
        // EFFECTS button — surfaces only on bulbs that expose effect_list. Label
        // includes the active effect name so the user can see what's running without
        // opening the picker. Tap requests the screen-level overlay via
        // [LocalOnOpenEffectPicker] — the actual picker sheet lives in CardStackScreen
        // so it can render full-screen above all card chrome rather than being clipped
        // to this row's bounds.
        if (effectList.isNotEmpty() && onOpenPicker != null) {
            if (visibleModes.size > 1) Spacer(Modifier.width(8.dp))
            val active = !currentEffect.isNullOrBlank()
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) accent else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onOpenPicker(entityId) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (active) "FX · ${currentEffect!!.uppercase()}" else "FX",
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

/**
 * Fullscreen overlay listing every effect from the bulb's `effect_list`, plus a NONE
 * entry at the top that clears the effect. The active effect is highlighted in accent.
 * Tapping any row applies it via [LocalOnSetLightEffect] and dismisses. A CLOSE row at
 * the bottom and a backdrop-tap also dismiss. Scrolls vertically so bulbs with long
 * effect lists (Nanoleaf can ship 30+) are usable on the R1's 320 px tall display.
 */
@Composable
internal fun EffectPickerSheet(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    current: String?,
    effects: List<String>,
    accent: Color,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // R1 system back dismisses the picker without applying.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // Backdrop — captures taps outside the list so the user can dismiss by tapping any
    // empty area. The list itself is full-bleed so there isn't actually much "outside",
    // but the CLOSE chip at the top right guarantees a discoverable dismiss path.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // Header row — title + a CLOSE chip on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "EFFECTS",
                    style = R1.sectionHeader,
                    color = R1.Ink,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Spacer(Modifier.height(10.dp))
            // Scrollable list of effects with NONE at the top.
            val scroll = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .androidxVerticalScroll(scroll),
            ) {
                EffectRow(label = "NONE", isActive = current.isNullOrBlank(), accent = accent) {
                    onPick(null)
                }
                effects.forEach { name ->
                    EffectRow(label = name, isActive = name == current, accent = accent) {
                        onPick(name)
                    }
                }
                // Bottom padding so the last row isn't flush with the screen edge,
                // making it harder to scroll past on touch.
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun EffectRow(label: String, isActive: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(R1.ShapeS)
            .background(if (isActive) accent else R1.SurfaceMuted)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Small radio-style indicator on the left so the active row is identifiable
            // even at a glance — accent fill on the chip helps but the bullet pins it.
            Text(
                text = if (isActive) "●" else "○",
                style = R1.labelMicro,
                color = if (isActive) R1.Bg else R1.InkSoft,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label.uppercase(),
                style = R1.body,
                color = if (isActive) R1.Bg else R1.Ink,
            )
        }
    }
}

/** Alias for `androidx.compose.foundation.verticalScroll` so the picker call site reads
 *  cleanly. Keeps the import surface small at the top of the file. */
private fun Modifier.androidxVerticalScroll(
    state: androidx.compose.foundation.ScrollState,
): Modifier = this.then(verticalScroll(state))

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
