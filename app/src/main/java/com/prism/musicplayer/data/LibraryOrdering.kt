package com.prism.musicplayer.data

import java.net.URLDecoder
import java.net.URLEncoder

/** What the search and sorting need to know about a song. [Song] has all of it. */
interface Sortable {
    val id: Long
    val title: String
    val artist: String
    val folderPath: String
    val sizeBytes: Long
    val addedSec: Long
}

enum class SortMode(val label: String) {
    TITLE_AZ("A to Z"),
    TITLE_ZA("Z to A"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Biggest first"),
    SMALLEST("Smallest first"),
    CUSTOM("My own order"),
}

enum class Move { UP, DOWN, TOP }

/**
 * Searching, sorting and rearranging the song list. No Android code in here, so it can be tested
 * on a normal computer (tools/library-test/run.sh).
 */
object LibraryOrdering {

    // ------------------------------------------------------------------ search

    /** A song matches if every word you typed appears somewhere in its title, artist or folder name. */
    fun matches(item: Sortable, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val text = "${item.title} ${item.artist} ${folderName(item.folderPath)}".lowercase()
        return words.all { it in text }
    }

    fun matches(album: AlbumFolder, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val text = album.name.lowercase()
        return words.all { it in text }
    }

    private fun words(query: String) = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/')

    // ----------------------------------------------------------------- sorting

    /** [custom] is the order the user arranged (a list of song ids). Songs it doesn't know go last, A to Z. */
    fun <T : Sortable> sort(items: List<T>, mode: SortMode, custom: List<Long>? = null): List<T> {
        val byTitle = compareBy<T>({ it.title.lowercase() }, { it.id })
        return when (mode) {
            SortMode.TITLE_AZ -> items.sortedWith(byTitle)
            SortMode.TITLE_ZA -> items.sortedWith(compareByDescending<T> { it.title.lowercase() }.thenBy { it.id })
            SortMode.NEWEST -> items.sortedWith(compareByDescending<T> { it.addedSec }.then(byTitle))
            SortMode.OLDEST -> items.sortedWith(compareBy<T> { it.addedSec }.then(byTitle))
            SortMode.LARGEST -> items.sortedWith(compareByDescending<T> { it.sizeBytes }.then(byTitle))
            SortMode.SMALLEST -> items.sortedWith(compareBy<T> { it.sizeBytes }.then(byTitle))
            SortMode.CUSTOM -> {
                val position = HashMap<Long, Int>()
                custom?.forEachIndexed { index, id -> position[id] = index }
                val (known, unknown) = items.partition { it.id in position }
                known.sortedBy { position[it.id] } + unknown.sortedWith(byTitle)
            }
        }
    }

    /** Albums follow the same sort choice. "My own order" only applies to songs, so albums go A to Z. */
    fun sortAlbums(albums: List<AlbumFolder>, mode: SortMode): List<AlbumFolder> {
        val byName = compareBy<AlbumFolder> { it.name.lowercase() }
        return when (mode) {
            SortMode.TITLE_AZ, SortMode.CUSTOM -> albums.sortedWith(byName)
            SortMode.TITLE_ZA -> albums.sortedWith(compareByDescending<AlbumFolder> { it.name.lowercase() })
            SortMode.NEWEST -> albums.sortedWith(compareByDescending<AlbumFolder> { it.newestAddedSec }.then(byName))
            SortMode.OLDEST -> albums.sortedWith(compareBy<AlbumFolder> { it.oldestAddedSec }.then(byName))
            SortMode.LARGEST -> albums.sortedWith(compareByDescending<AlbumFolder> { it.totalBytes }.then(byName))
            SortMode.SMALLEST -> albums.sortedWith(compareBy<AlbumFolder> { it.totalBytes }.then(byName))
        }
    }

    // ------------------------------------------------------------- rearranging

    /** Moves one song up, down, or to the top of [ids]. */
    fun moved(ids: List<Long>, id: Long, how: Move): List<Long> {
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val list = ids.toMutableList()
        list.removeAt(from)
        val to = when (how) {
            Move.UP -> (from - 1).coerceAtLeast(0)
            Move.DOWN -> (from + 1).coerceAtMost(list.size)
            Move.TOP -> 0
        }
        list.add(to, id)
        return list
    }

    // ------------------------------------------------- saving the user's orders

    /** One line per album: "<folder, made safe>=<id>,<id>,...". The empty folder name means "all music". */
    fun encodeOrders(orders: Map<String, List<Long>>): String =
        orders.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("\n") { (folder, ids) -> URLEncoder.encode(folder, "UTF-8") + "=" + ids.joinToString(",") }

    /** Reads what [encodeOrders] wrote. Broken lines are skipped. */
    fun decodeOrders(text: String): Map<String, List<Long>> {
        val result = LinkedHashMap<String, List<Long>>()
        for (line in text.lines()) {
            val cut = line.indexOf('=')
            if (cut < 0) continue
            val folder = try {
                URLDecoder.decode(line.substring(0, cut), "UTF-8")
            } catch (e: IllegalArgumentException) {
                continue
            }
            val ids = line.substring(cut + 1).split(',').mapNotNull { it.trim().toLongOrNull() }
            if (ids.isNotEmpty()) result[folder] = ids
        }
        return result
    }
}
