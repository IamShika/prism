package com.prism.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.prism.musicplayer.data.Song

/**
 * What changes while a row is being dragged. The list shown on screen is [working]; it is only
 * handed to the app (and saved) when the finger lets go, so dragging stays smooth.
 */
private class ReorderState(initial: List<Song>, private val listState: LazyListState) {
    var working by mutableStateOf(initial)
    var draggedKey by mutableStateOf<Long?>(null)
    var dragOffset by mutableFloatStateOf(0f)

    fun start(key: Long) {
        draggedKey = key
        dragOffset = 0f
    }

    fun dragBy(dy: Float) {
        dragOffset += dy
        trySwap()
    }

    /** Trades places with the row under the dragged row, if there is one. */
    fun trySwap() {
        val key = draggedKey ?: return
        val rows = listState.layoutInfo.visibleItemsInfo
        val dragged = rows.firstOrNull { it.key == key } ?: return
        // right after a trade the screen needs one more frame to catch up; wait for it
        if (dragged.index != working.indexOfFirst { it.id == key }) return

        val spans = rows.map { ItemSpan(it.index, it.offset, it.size) }
        val result = DragMath.swap(spans, dragged.index, dragOffset) ?: return
        working = DragMath.moveItem(working, dragged.index, result.toIndex)
        dragOffset = result.newDragOffset
    }

    /** How far to scroll this frame: non-zero while the dragged row is held near the top or bottom edge. */
    fun autoScrollStep(): Float {
        val key = draggedKey ?: return 0f
        val layout = listState.layoutInfo
        val dragged = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        val top = dragged.offset + dragOffset
        return DragMath.autoScroll(
            rowTop = top,
            rowBottom = top + dragged.size,
            viewHeight = layout.viewportSize.height.toFloat(),
            edge = dragged.size.toFloat(),
            maxStep = 30f,
        )
    }

    fun finish(): List<Long> {
        draggedKey = null
        dragOffset = 0f
        return working.map { it.id }
    }
}

/**
 * The song list while the user arranges it by hand. Drag the handle on the right to move a song.
 * Hold a song near the top or bottom edge to scroll. [onReorder] gets the new order when the finger lets go.
 */
@Composable
fun ArrangeList(
    songs: List<Song>,
    onReorder: (List<Long>) -> Unit,
    onTop: (Song) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorder = remember { ReorderState(songs, listState) }
    val haptic = LocalHapticFeedback.current
    val latestSongs by rememberUpdatedState(songs)
    val latestOnReorder by rememberUpdatedState(onReorder)

    // The order changed from outside (e.g. the "Top" button, or the drop was saved): show it, unless a drag is going on
    LaunchedEffect(songs) {
        if (reorder.draggedKey == null) reorder.working = songs
    }

    // One step per screen frame while dragging: finish pending trades, and scroll if the row is near an edge
    val dragging = reorder.draggedKey != null
    LaunchedEffect(dragging) {
        while (reorder.draggedKey != null) {
            withFrameNanos { }
            reorder.trySwap()
            val step = reorder.autoScrollStep()
            if (step != 0f) {
                val moved = listState.scrollBy(step)
                reorder.dragOffset += moved // the row stays under the finger while the list moves
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
    ) {
        items(reorder.working, key = { it.id }) { song ->
            val isDragged = reorder.draggedKey == song.id
            ArrangeRow(
                song = song,
                isDragged = isDragged,
                offset = { if (isDragged) reorder.dragOffset else 0f },
                onTop = { onTop(song) },
                onDragStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    reorder.start(song.id)
                },
                onDrag = reorder::dragBy,
                onDragEnd = {
                    val ids = reorder.finish()
                    if (ids != latestSongs.map { it.id }) latestOnReorder(ids)
                },
            )
        }
    }
}

@Composable
private fun ArrangeRow(
    song: Song,
    isDragged: Boolean,
    offset: () -> Float,
    onTop: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // the gesture below is set up once, so it must always call the newest versions of these
    val startNow by rememberUpdatedState(onDragStart)
    val dragNow by rememberUpdatedState(onDrag)
    val endNow by rememberUpdatedState(onDragEnd)
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                translationY = offset()
                shadowElevation = if (isDragged) 12.dp.toPx() else 0f
            }
            .background(if (isDragged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
            .padding(start = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Top",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onTop).padding(horizontal = 10.dp, vertical = 14.dp),
        )
        // the drag handle: three lines
        Box(
            modifier = Modifier
                .size(width = 60.dp, height = 56.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startNow() },
                        onDrag = { change, amount ->
                            change.consume()
                            dragNow(amount.y)
                        },
                        onDragEnd = { endNow() },
                        onDragCancel = { endNow() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width = 24.dp, height = 14.dp)) {
                val stroke = 2.dp.toPx()
                listOf(0f, size.height / 2f, size.height).forEach { y ->
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}
