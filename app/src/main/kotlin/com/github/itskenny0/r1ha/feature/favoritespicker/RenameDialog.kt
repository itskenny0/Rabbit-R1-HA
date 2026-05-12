package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Per-entity customization dialog. Combines the name override + display visibility
 * toggles + long-press action into a single scrollable panel — the user expects a
 * "customize" entry point and the rename + display + gesture options together are it.
 *
 * Each section is independent: NAME drives [EntityOverride] nothing (lives in the
 * separate [com.github.itskenny0.r1ha.core.prefs.AppSettings.nameOverrides] map),
 * DISPLAY toggles the per-card visibility overrides, GESTURE configures the long-press
 * action. Save persists all sections atomically; cancel discards every change.
 *
 * Built from R1 primitives — sharp 2dp slots, hairline borders, monospace mono details
 * — so the customize surface stays inside the dashboard language instead of becoming a
 * generic Material settings page.
 */
@Composable
fun RenameDialog(
    entity: EntityState,
    initialName: String,
    initialOverride: EntityOverride,
    onSave: (name: String, override: EntityOverride) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(entity.id.value) { mutableStateOf(initialName) }
    var override by remember(entity.id.value) { mutableStateOf(initialOverride) }
    BackHandler(onBack = onCancel)
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dim the picker behind so the customize surface reads as a modal. r1Pressable
            // on the backdrop with `hapticOnClick = false` — tapping outside the inner
            // card dismisses without a haptic that might suggest a confirm.
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onCancel, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        // Inner panel — block the outer dismiss-on-tap by absorbing the click via its own
        // pressable that does nothing on click. Otherwise tapping the text field's empty
        // padding would dismiss the dialog mid-edit.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            // Header — title + entity_id reminder so the user is sure they're editing
            // the right entity (critical when several have similar friendly names).
            Text(text = "CUSTOMIZE", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.id.value,
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            // ── NAME ─────────────────────────────────────────────────────────────────
            SectionHeader("NAME")
            R1TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = entity.friendlyName,
                monospace = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions.Default,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Local-only — clear to revert to HA's friendly_name.",
                style = R1.body,
                color = R1.InkMuted,
            )

            // ── DISPLAY ──────────────────────────────────────────────────────────────
            SectionHeader("DISPLAY")
            TristateRow(
                label = "Show on/off pill",
                value = override.showOnOffPill,
                onChange = { override = override.copy(showOnOffPill = it) },
            )
            Spacer(Modifier.height(8.dp))
            TristateRow(
                label = "Show area label",
                value = override.showAreaLabel,
                onChange = { override = override.copy(showAreaLabel = it) },
            )

            // ── TEXT SIZE ──────────────────────────────────────────────────────────
            SectionHeader("TEXT SIZE")
            Text(
                text = "Scale the big readout on this card without affecting siblings.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            TextScaleRow(
                selected = override.textScale,
                onSelect = { override = override.copy(textScale = it) },
            )

            // ── COLOUR ──────────────────────────────────────────────────────────────
            SectionHeader("COLOUR")
            Text(
                text = "Override the card's accent tone. DEFAULT = domain colour.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            ColourSwatchRow(
                selected = override.accentColor,
                onSelect = { override = override.copy(accentColor = it) },
            )

            // ── DECIMALS (sensors only — the option is meaningless on other entities)
            if (entity.id.domain.isSensor) {
                SectionHeader("DECIMALS")
                Text(
                    text = "Trim noisy precise readings. DEFAULT inherits the global setting.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(6.dp))
                DecimalSegmentedRow(
                    selected = override.maxDecimalPlaces,
                    onSelect = { override = override.copy(maxDecimalPlaces = it) },
                )
            }

            // ── GESTURE ──────────────────────────────────────────────────────────────
            SectionHeader("GESTURE")
            Text(
                text = "Long-press this card to fire another entity. e.g. `scene.movie_night`, `script.bedtime`, `switch.kettle`.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(6.dp))
            R1TextField(
                value = override.longPressTarget.orEmpty(),
                onValueChange = { v -> override = override.copy(longPressTarget = v.takeIf { it.isNotBlank() }) },
                placeholder = "scene.movie_night",
                monospace = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onSave(name, override) },
                ),
            )

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                R1Button(
                    text = "CANCEL",
                    onClick = onCancel,
                    variant = R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.width(8.dp))
                R1Button(
                    text = "SAVE",
                    onClick = { onSave(name, override) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(text = title, style = R1.sectionHeader, color = R1.InkSoft)
    Spacer(Modifier.height(2.dp))
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(R1.Hairline))
    Spacer(Modifier.height(8.dp))
}

/**
 * Three-state segmented picker for nullable booleans: DEFAULT (null, inherit global) /
 * SHOW (true, force visible) / HIDE (false, force hidden). The asymmetric labels make
 * the "inherit global setting" semantics easier to read than a plain on/off switch.
 */
@Composable
private fun TristateRow(
    label: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().clip(R1.ShapeS).background(R1.SurfaceMuted)) {
            TristateCell(text = "DEFAULT", selected = value == null, onClick = { onChange(null) })
            CellDivider()
            TristateCell(text = "SHOW", selected = value == true, onClick = { onChange(true) })
            CellDivider()
            TristateCell(text = "HIDE", selected = value == false, onClick = { onChange(false) })
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TristateCell(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .background(if (selected) R1.AccentWarm else R1.SurfaceMuted)
            .r1Pressable(onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = R1.labelMicro,
            color = if (selected) R1.Bg else R1.InkSoft,
        )
    }
}

@Composable
private fun CellDivider() {
    Box(modifier = Modifier.width(1.dp).height(34.dp).background(R1.Bg))
}

/** Horizontal-scrolling swatch row for the per-card accent colour. First chip resets to
 *  default (domain colour); the rest are pulled from [EntityOverride.ACCENT_PALETTE]. */
@Composable
private fun ColourSwatchRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // DEFAULT chip — wider than the swatches so it reads as a text label rather than
        // an unidentified colour-less square.
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .clip(R1.ShapeS)
                .background(if (selected == null) R1.AccentWarm else R1.Bg)
                .let { m -> if (selected == null) m else m.border(1.dp, R1.Hairline, R1.ShapeS) }
                .r1Pressable({ onSelect(null) })
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "DEFAULT",
                style = R1.labelMicro,
                color = if (selected == null) R1.Bg else R1.InkSoft,
            )
        }
        EntityOverride.ACCENT_PALETTE.forEach { (label, argb) ->
            val isSelected = selected == argb
            // Each swatch is a coloured square; selected one gets a hairline accent ring
            // so the choice reads at a glance.
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(androidx.compose.ui.graphics.Color(argb))
                    .let { m ->
                        if (isSelected) m.border(2.dp, R1.Ink, R1.ShapeS)
                        else m
                    }
                    .r1Pressable({ onSelect(argb) }),
            )
            // `label` is unused visually but kept for future "hover label" UX —
            // currently swatch is just colour. Suppress unused with a no-op reference.
            @Suppress("UNUSED_EXPRESSION") label
        }
    }
}

@Composable
private fun TextScaleRow(
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().clip(R1.ShapeS).background(R1.SurfaceMuted)) {
        EntityOverride.TEXT_SCALES.forEachIndexed { idx, scale ->
            // Label uses the multiplier rounded to nearest 0.05 so users see "0.7x" not
            // "0.7f"; matches our shorthand for visual scale.
            val label = when (scale) {
                1.0f -> "1×"
                else -> "%.2fx".format(scale)
            }
            TristateCell(
                text = label,
                selected = kotlin.math.abs(selected - scale) < 0.01f,
                onClick = { onSelect(scale) },
            )
            if (idx < EntityOverride.TEXT_SCALES.lastIndex) CellDivider()
        }
    }
}

@Composable
private fun DecimalSegmentedRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().clip(R1.ShapeS).background(R1.SurfaceMuted)) {
        TristateCell(text = "DEFAULT", selected = selected == null, onClick = { onSelect(null) })
        CellDivider()
        listOf(0, 1, 2, 3, 4).forEachIndexed { idx, n ->
            TristateCell(
                text = if (n == 0) "INT" else "$n",
                selected = selected == n,
                onClick = { onSelect(n) },
            )
            if (idx < 4) CellDivider()
        }
    }
}

