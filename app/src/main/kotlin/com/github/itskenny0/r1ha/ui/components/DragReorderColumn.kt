package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * LazyColumn with drag-and-drop reorder. Long-press a row to grab it, drag to move,
 * release to drop. The dragged row gets a subtle elevation/translation while in
 * flight; other rows slide into their new slots via Compose's built-in
 * [LazyItemScope.animateItem] so the reorder feels physical rather than jumpy.
 *
 * Live-swap strategy (half-row threshold) rather than absolute-position drag:
 *  - Long-press starts the drag, [dragOffset] accumulates per-frame finger Δy
 *  - When the cumulative offset crosses ±½ rowHeight, swap the dragged item with
 *    its immediate neighbour and reset the offset to ±½ in the other direction
 *  - Repeat for as many neighbours as the finger crosses
 *
 * This produces a "step" feel that matches how the eye expects rows to swap, and
 * it side-steps the more complex math of placing the dragged item at an arbitrary
 * pixel-Y. The dataset is reordered immediately on each swap (via [onReorder]) so
 * the source-of-truth and the visible order stay in sync the whole time.
 *
 * The composable owns the LazyListState so callers don't have to thread one
 * through — the [itemsIndexed] lookup of `key` against the visible-items list is
 * the only state-y bit we need internally.
 */
@Composable
fun <T : Any> DragReorderColumn(
    items: List<T>,
    keyOf: (T) -> Any,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Optional caller-owned LazyListState. Lift this when the caller needs to drive
     * scroll position from outside the list — e.g. hooking the wheel input up to
     * animateScrollBy so a wheel spin scrolls the picker list rather than reaching
     * past it to mutate the card underneath the overlay. Null = a list-local state
     * is allocated, which is the simpler case for self-contained pickers.
     */
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    /**
     * Row body. [dragHandle] is the Modifier the consumer applies to the part of the
     * row that initiates the drag (typically the whole row, but it could be just a
     * leading icon). [isDragging] flips true on the row currently in flight so the
     * consumer can tweak visuals (e.g. add accent border, hide ON/OFF labels). The
     * [LazyItemScope] receiver gives access to `animateItem()` for smooth row reflow.
     */
    itemContent: @Composable LazyItemScope.(item: T, dragHandle: Modifier, isDragging: Boolean) -> Unit,
) {
    // Identity of the row being dragged — null when no drag in flight. We track by
    // the consumer's stable key (rather than by index) because the index shifts as
    // swaps land; the key is the only stable handle on the row across reorderings.
    var draggedKey by remember { mutableStateOf<Any?>(null) }
    // Current index of the dragged row in [items]. Updated synchronously after each
    // swap so the next neighbour-distance check uses the right base.
    var draggedIndex by remember { mutableIntStateOf(-1) }
    // Cumulative finger-Y since drag start, in pixels. Half-row threshold compared
    // against the dragged row's measured pixel height (from layoutInfo).
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(items, key = { _, item -> keyOf(item) }) { idx, item ->
            val key = keyOf(item)
            val isDragging = key == draggedKey
            // Build the per-row pointer modifier. Re-created per recomposition is
            // fine — pointerInput is keyed by `key` so the gesture coroutine
            // restarts only when the item identity changes.
            val handleMod = Modifier.pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        draggedKey = key
                        draggedIndex = idx
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        draggedKey = null
                        draggedIndex = -1
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        draggedKey = null
                        draggedIndex = -1
                        dragOffset = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount.y
                        // Look up the dragged row's pixel height for the swap
                        // threshold. Falls back to 56 px when the row is briefly
                        // off-screen (drag carrying it past the viewport edge);
                        // 56 dp ≈ 56 px on the R1's 1:1 density baseline so this
                        // approximation is close enough not to feel jumpy.
                        val rowInfo = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == draggedKey }
                        val rowHeight = rowInfo?.size?.toFloat() ?: 56f
                        val threshold = rowHeight / 2f
                        // Walk multiple swaps per frame if the finger is moving
                        // fast — fast spins should still keep up rather than
                        // accumulating a huge offset and snapping at drag-end.
                        while (dragOffset > threshold && draggedIndex < items.lastIndex) {
                            onReorder(draggedIndex, draggedIndex + 1)
                            draggedIndex += 1
                            dragOffset -= rowHeight
                        }
                        while (dragOffset < -threshold && draggedIndex > 0) {
                            onReorder(draggedIndex, draggedIndex - 1)
                            draggedIndex -= 1
                            dragOffset += rowHeight
                        }
                    },
                )
            }
            Box(
                modifier = Modifier
                    .let { base ->
                        // Animate non-dragged rows into their new positions when
                        // their neighbour swaps. The dragged row itself uses a
                        // graphicsLayer translation (below) so the two animation
                        // strategies don't conflict.
                        if (isDragging) base else base.then(Modifier.animateItem())
                    }
                    .let { base ->
                        if (isDragging) base.graphicsLayer {
                            translationY = dragOffset
                            shadowElevation = 14f
                            alpha = 0.92f
                        } else base
                    },
            ) {
                itemContent(item, handleMod, isDragging)
            }
        }
    }
}
