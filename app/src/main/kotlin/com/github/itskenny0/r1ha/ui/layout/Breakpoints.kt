package com.github.itskenny0.r1ha.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width-based responsive tier the current composition is rendering in.
 *
 * The R1's native screen reports ~320–340 dp wide depending on the active
 * `wm density`, so anything ≤ 360 dp is treated as **R1** and rendered
 * exactly as before (no regressions on the R1's small portrait display).
 *
 * `PHONE` covers most modern phones (typically 360–500 dp portrait); the
 * layout stays single-column but gets a small breathing-room cap so a
 * landscape phone or a tall narrow tablet column doesn't stretch tiles
 * absurdly wide. `TABLET` is everything above — centred narrow column
 * (or a 2-column dashboard) so an 8″ tablet doesn't render a single card
 * across 1200 dp.
 *
 * Thresholds match the Material 3 window-size-class breakpoints loosely
 * — but we keep the lower one at 360 dp so the R1 sits squarely in the
 * smallest bucket regardless of LineageOS GSI density tweaks.
 */
enum class WidthTier {
    /** ≤ 360 dp — R1 native portrait. Layout is rendered exactly as
     *  written; no max-width clamp, no extra padding. */
    R1,
    /** 361–599 dp — most phones in portrait. Single column with a
     *  light max-width cap so a held landscape phone doesn't get
     *  overstretched rows. */
    PHONE,
    /** ≥ 600 dp — tablets, foldables, large landscape phones. Content
     *  is centred with a tighter max-width cap. Some screens (Cameras
     *  GRID, Dashboard tile row) can also opt into a wider grid. */
    TABLET,
}

/** Reads the current screen width and maps it to a [WidthTier]. Cheap —
 *  pulls from [LocalConfiguration] which is already part of every Compose
 *  call site. */
@Composable
@ReadOnlyComposable
fun currentWidthTier(): WidthTier {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w <= 360 -> WidthTier.R1
        w < 600 -> WidthTier.PHONE
        else -> WidthTier.TABLET
    }
}

/** Convenience — `true` when the host is bigger than an R1. Use sparingly;
 *  most screens should route through [currentWidthTier] for the actual
 *  layout decision. */
@Composable
@ReadOnlyComposable
fun isWiderThanR1(): Boolean = currentWidthTier() != WidthTier.R1

/** Per-tier max content width for the single-column responsive
 *  container. R1 returns `Dp.Unspecified` so existing screens render
 *  bit-for-bit identical to today; PHONE and TABLET get bounded so a
 *  wide screen doesn't stretch the layout horizontally. */
@Composable
@ReadOnlyComposable
fun maxContentWidthFor(tier: WidthTier): Dp = when (tier) {
    WidthTier.R1 -> Dp.Unspecified
    WidthTier.PHONE -> 480.dp
    WidthTier.TABLET -> 560.dp
}

/**
 * Centred narrow-column wrapper for screens whose internal layout was
 * designed around the R1's ~320 dp width.
 *
 * On R1 (`tier == R1`) this is a passthrough — the wrapped composable
 * fills the full width with zero padding, exactly matching pre-change
 * behaviour. On larger screens the content is constrained to
 * [maxContentWidthFor]`(tier)` and horizontally centred, with a small
 * outer padding so the centred column doesn't sit flush against the
 * device bezel.
 *
 * Call sites pass their normal `Modifier.fillMaxSize()` etc. into the
 * lambda; the wrapper takes care of the centring.
 */
@Composable
fun ResponsiveColumn(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable () -> Unit,
) {
    val tier = currentWidthTier()
    if (tier == WidthTier.R1) {
        // Passthrough — same Box-less layout the screen would have had
        // before this wrapper existed. Critical for not breaking the R1.
        content()
        return
    }
    val maxWidth = maxContentWidthFor(tier)
    Box(
        modifier = modifier
            .fillMaxSize()
            // Side padding so the centred column has breathing room
            // from the bezel on wide displays. 16 dp is the same
            // gutter most material patterns use.
            .padding(horizontal = 16.dp),
        contentAlignment = contentAlignment,
    ) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxSize()) {
            content()
        }
    }
}

/** Column count for grid surfaces (Cameras GRID, future favourites
 *  picker grid, etc.). R1 stays at 2 columns; phones widen to 2; tablets
 *  go to 3 so the extra horizontal space is actually used. Returning an
 *  Int keeps call sites pleasingly terse: `columns = GridCells.Fixed(gridColumnsFor(tier))`. */
@Composable
@ReadOnlyComposable
fun gridColumnsFor(tier: WidthTier = currentWidthTier()): Int = when (tier) {
    WidthTier.R1 -> 2
    WidthTier.PHONE -> 2
    WidthTier.TABLET -> 3
}
