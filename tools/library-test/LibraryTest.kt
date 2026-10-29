// Offline checks for search, sorting and rearranging. Runs on a normal computer, no phone needed.
// See tools/library-test/run.sh
import com.prism.musicplayer.data.AlbumFolder
import com.prism.musicplayer.data.LibraryOrdering
import com.prism.musicplayer.data.Move
import com.prism.musicplayer.data.SortMode
import com.prism.musicplayer.data.Sortable

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

class T(
    override val id: Long,
    override val title: String,
    override val artist: String = "",
    override val folderPath: String = "",
    override val sizeBytes: Long = 0,
    override val addedSec: Long = 0,
) : Sortable

fun ids(list: List<T>) = list.map { it.id }

fun main() {
    println("Library ordering tests\n")

    val songs = listOf(
        T(1, "banana", "Zed", "/m/Road Trip", sizeBytes = 5_000, addedSec = 300),
        T(2, "Apple", "Yan", "/m/Chill", sizeBytes = 9_000, addedSec = 100),
        T(3, "cherry", "Xu", "/m/Road Trip", sizeBytes = 1_000, addedSec = 200),
        T(4, "Apple", "Wu", "/m/Chill", sizeBytes = 9_000, addedSec = 100),
    )

    // ---- search
    check("Empty search shows everything", songs.all { LibraryOrdering.matches(it, "") && LibraryOrdering.matches(it, "   ") })
    check("Search finds by title, ignoring capitals", ids(songs.filter { LibraryOrdering.matches(it, "APPLE") }) == listOf(2L, 4L))
    check("Search finds by artist", ids(songs.filter { LibraryOrdering.matches(it, "zed") }) == listOf(1L))
    check("Search finds by album folder", ids(songs.filter { LibraryOrdering.matches(it, "road") }) == listOf(1L, 3L))
    check("Several words must all match", ids(songs.filter { LibraryOrdering.matches(it, "apple wu") }) == listOf(4L))
    check("Words can match in different fields", ids(songs.filter { LibraryOrdering.matches(it, "cherry road") }) == listOf(3L))
    check("No match gives nothing", songs.none { LibraryOrdering.matches(it, "zzzz") })
    check("Search works with other alphabets", LibraryOrdering.matches(T(9, "ເພງຂອງຂ້ອຍ"), "ເພງ"))
    val albums = listOf(AlbumFolder("/m/Chill", 2, 1, 18_000, 100, 100), AlbumFolder("/m/Road Trip", 2, 1, 6_000, 300, 200))
    check("Search finds albums by name", albums.filter { LibraryOrdering.matches(it, "road") }.map { it.name } == listOf("Road Trip"))

    // ---- sorting songs
    check("A to Z (capitals ignored)", ids(LibraryOrdering.sort(songs, SortMode.TITLE_AZ)) == listOf(2L, 4L, 1L, 3L), ids(LibraryOrdering.sort(songs, SortMode.TITLE_AZ)).toString())
    check("Z to A", ids(LibraryOrdering.sort(songs, SortMode.TITLE_ZA)) == listOf(3L, 1L, 2L, 4L), ids(LibraryOrdering.sort(songs, SortMode.TITLE_ZA)).toString())
    check("Newest first", ids(LibraryOrdering.sort(songs, SortMode.NEWEST)) == listOf(1L, 3L, 2L, 4L), ids(LibraryOrdering.sort(songs, SortMode.NEWEST)).toString())
    check("Oldest first", ids(LibraryOrdering.sort(songs, SortMode.OLDEST)) == listOf(2L, 4L, 3L, 1L), ids(LibraryOrdering.sort(songs, SortMode.OLDEST)).toString())
    check("Biggest first", ids(LibraryOrdering.sort(songs, SortMode.LARGEST)) == listOf(2L, 4L, 1L, 3L), ids(LibraryOrdering.sort(songs, SortMode.LARGEST)).toString())
    check("Smallest first", ids(LibraryOrdering.sort(songs, SortMode.SMALLEST)) == listOf(3L, 1L, 2L, 4L), ids(LibraryOrdering.sort(songs, SortMode.SMALLEST)).toString())
    check("Sorting never loses or duplicates songs", SortMode.values().all { m -> LibraryOrdering.sort(songs, m, listOf(4, 1)).map { it.id }.sorted() == listOf(1L, 2L, 3L, 4L) })
    check("Sorting doesn't change the original list", ids(songs) == listOf(1L, 2L, 3L, 4L))

    // ---- custom order
    val custom = listOf(3L, 1L)
    check("My own order: arranged songs first, in that order", ids(LibraryOrdering.sort(songs, SortMode.CUSTOM, custom)).take(2) == listOf(3L, 1L))
    check("My own order: songs not arranged yet go last, A to Z", ids(LibraryOrdering.sort(songs, SortMode.CUSTOM, custom)) == listOf(3L, 1L, 2L, 4L))
    check("My own order with nothing saved falls back to A to Z", ids(LibraryOrdering.sort(songs, SortMode.CUSTOM, null)) == listOf(2L, 4L, 1L, 3L))
    check("My own order ignores ids of songs that are gone", ids(LibraryOrdering.sort(songs, SortMode.CUSTOM, listOf(99, 3, 98, 1))) == listOf(3L, 1L, 2L, 4L))

    // ---- sorting albums
    fun names(l: List<AlbumFolder>) = l.map { it.name }
    check("Albums A to Z", names(LibraryOrdering.sortAlbums(albums, SortMode.TITLE_AZ)) == listOf("Chill", "Road Trip"))
    check("Albums Z to A", names(LibraryOrdering.sortAlbums(albums, SortMode.TITLE_ZA)) == listOf("Road Trip", "Chill"))
    check("Albums newest first", names(LibraryOrdering.sortAlbums(albums, SortMode.NEWEST)) == listOf("Road Trip", "Chill"))
    check("Albums oldest first", names(LibraryOrdering.sortAlbums(albums, SortMode.OLDEST)) == listOf("Chill", "Road Trip"))
    check("Albums biggest first", names(LibraryOrdering.sortAlbums(albums, SortMode.LARGEST)) == listOf("Chill", "Road Trip"))
    check("Albums smallest first", names(LibraryOrdering.sortAlbums(albums, SortMode.SMALLEST)) == listOf("Road Trip", "Chill"))
    check("Albums in 'my own order' mode go A to Z", names(LibraryOrdering.sortAlbums(albums, SortMode.CUSTOM)) == listOf("Chill", "Road Trip"))

    // ---- moving
    val row = listOf(10L, 20L, 30L, 40L)
    check("Move up", LibraryOrdering.moved(row, 30, Move.UP) == listOf(10L, 30L, 20L, 40L))
    check("Move down", LibraryOrdering.moved(row, 20, Move.DOWN) == listOf(10L, 30L, 20L, 40L))
    check("Move to top", LibraryOrdering.moved(row, 40, Move.TOP) == listOf(40L, 10L, 20L, 30L))
    check("First song can't go up", LibraryOrdering.moved(row, 10, Move.UP) == row)
    check("Last song can't go down", LibraryOrdering.moved(row, 40, Move.DOWN) == row)
    check("Top song to top changes nothing", LibraryOrdering.moved(row, 10, Move.TOP) == row)
    check("Unknown song changes nothing", LibraryOrdering.moved(row, 99, Move.UP) == row)
    check("Moving keeps every song exactly once", LibraryOrdering.moved(row, 20, Move.DOWN).sorted() == row)

    // ---- saving
    val orders = mapOf("" to listOf(3L, 1L, 2L), "/storage/emulated/0/Music/Road Trip" to listOf(7L, 5L), "/weird=name;with&chars é/日本" to listOf(1L))
    val text = LibraryOrdering.encodeOrders(orders)
    check("Orders survive saving and loading, even odd folder names", LibraryOrdering.decodeOrders(text) == orders, text.replace("\n", " | "))
    check("Nothing saved loads as nothing", LibraryOrdering.decodeOrders("").isEmpty())
    check("Broken lines are skipped", LibraryOrdering.decodeOrders("nonsense\n%ZZ=1,2\nok=4,x,5\nempty=\n") == mapOf("ok" to listOf(4L, 5L)))
    check("Empty orders aren't saved", LibraryOrdering.encodeOrders(mapOf("a" to emptyList())) == "")

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
