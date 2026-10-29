package com.prism.musicplayer.ui

/** Where one row of the list is on screen: [offset] pixels from the top, [size] pixels tall. */
class ItemSpan(val index: Int, val offset: Int, val size: Int)

/** What to do when the dragged row has moved over another row: put it at [toIndex], and set the drag offset to [newDragOffset]. */
class SwapResult(val toIndex: Int, val newDragOffset: Float)

/**
 * The maths behind dragging a row to a new place. No Android code, so it can be tested on a
 * normal computer (tools/drag-test/run.sh). All rows are assumed to be the same height, which is
 * true for the song list.
 */
object DragMath {

    /**
     * The finger has pulled the row [draggedIndex] by [dragOffset] pixels from where the list put it.
     * If the middle of the row is now over another row, they trade places.
     *
     * After the trade, the list puts the dragged row in a new natural spot. [SwapResult.newDragOffset]
     * is adjusted by exactly that difference, so the row stays under the finger and doesn't jump.
     * Returns null if nothing should change.
     */
    fun swap(spans: List<ItemSpan>, draggedIndex: Int, dragOffset: Float): SwapResult? {
        val dragged = spans.firstOrNull { it.index == draggedIndex } ?: return null
        val center = dragged.offset + dragOffset + dragged.size / 2f
        val over = spans.firstOrNull { center >= it.offset && center < it.offset + it.size } ?: return null
        if (over.index == draggedIndex) return null

        // where the list will put the dragged row once it has moved to the other row's place
        val newOffset = if (over.index > dragged.index) over.offset + over.size - dragged.size else over.offset
        return SwapResult(over.index, dragOffset + dragged.offset - newOffset)
    }

    /**
     * How far to scroll the list this frame while a row is held near the top or bottom edge.
     * Negative = up, positive = down, 0 = not near an edge. Faster the closer to the edge.
     */
    fun autoScroll(rowTop: Float, rowBottom: Float, viewHeight: Float, edge: Float, maxStep: Float): Float {
        if (edge <= 0f) return 0f
        val fromTop = rowTop
        val fromBottom = viewHeight - rowBottom
        return when {
            fromTop < edge -> -maxStep * ((edge - fromTop) / edge).coerceIn(0f, 1f)
            fromBottom < edge -> maxStep * ((edge - fromBottom) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /** Moves the item at [from] to [to] in a copy of the list. */
    fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val copy = list.toMutableList()
        copy.add(to, copy.removeAt(from))
        return copy
    }
}
