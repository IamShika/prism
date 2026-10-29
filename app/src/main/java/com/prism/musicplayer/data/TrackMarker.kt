package com.prism.musicplayer.data

/**
 * Where one song should start and end playing. Saved per song, so the silence before the music
 * (or after it) is skipped every time.
 *
 * [startMs] = 0 means "from the beginning". [endMs] = 0 means "until the real end".
 * No Android code in here, so it can be tested on a normal computer (tools/marker-test/run.sh).
 */
data class TrackMarker(val startMs: Long = 0L, val endMs: Long = 0L) {

    /** True if the user changed anything for this song. */
    val isSet: Boolean get() = startMs > 0L || endMs > 0L

    /** The end position to use, given the song's full length. */
    fun effectiveEnd(fullMs: Long): Long = if (endMs > 0L) endMs else fullMs

    /** How long the song plays with these markers. */
    fun clipLengthMs(fullMs: Long): Long = (effectiveEnd(fullMs) - startMs).coerceAtLeast(0L)
}

object TrackMarkers {

    /** A song can't be trimmed shorter than this. */
    const val MIN_CLIP_MS = 1000L

    /** A start or end marker this close to the real edge counts as "not set". */
    private const val SNAP_MS = 150L

    /** Moves the start marker. It can't pass the end marker (or the end of the song). */
    fun withStart(old: TrackMarker, newStartMs: Long, fullMs: Long): TrackMarker {
        val limit = (old.effectiveEnd(fullMs) - MIN_CLIP_MS).coerceAtLeast(0L)
        val start = newStartMs.coerceIn(0L, limit)
        return old.copy(startMs = if (start <= SNAP_MS) 0L else start)
    }

    /** Moves the end marker. It can't come before the start marker, or go past the end of the song. */
    fun withEnd(old: TrackMarker, newEndMs: Long, fullMs: Long): TrackMarker {
        if (newEndMs <= 0L) return old.copy(endMs = 0L)
        val low = old.startMs + MIN_CLIP_MS
        val end = if (low >= fullMs) fullMs else newEndMs.coerceIn(low, fullMs)
        return old.copy(endMs = if (fullMs - end <= SNAP_MS) 0L else end)
    }

    // ---- saving: "id,start,end;id,start,end"

    fun encode(markers: Map<Long, TrackMarker>): String =
        markers.entries
            .filter { it.value.isSet }
            .joinToString(";") { "${it.key},${it.value.startMs},${it.value.endMs}" }

    /** Reads what [encode] wrote. Broken entries are skipped, so a damaged file can't crash the app. */
    fun decode(text: String): Map<Long, TrackMarker> {
        val result = mutableMapOf<Long, TrackMarker>()
        for (entry in text.split(';')) {
            val parts = entry.split(',')
            if (parts.size != 3) continue
            val id = parts[0].trim().toLongOrNull() ?: continue
            if (id < 0) continue // -1 means "not a library song"
            val start = parts[1].trim().toLongOrNull() ?: continue
            val end = parts[2].trim().toLongOrNull() ?: continue
            if (start < 0 || end < 0) continue
            val marker = TrackMarker(start, end)
            if (marker.isSet) result[id] = marker
        }
        return result
    }
}
