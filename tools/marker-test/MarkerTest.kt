// Offline checks for the start/end marker rules. Runs on a normal computer, no phone needed.
// See tools/marker-test/run.sh
import com.prism.musicplayer.data.TrackMarker
import com.prism.musicplayer.data.TrackMarkers

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

fun main() {
    println("Track marker tests\n")
    val full = 200_000L // a 3:20 song
    val none = TrackMarker()

    check("Nothing set by default", !none.isSet && none.clipLengthMs(full) == full)
    check("Start only counts as set", TrackMarker(3_000, 0).isSet)
    check("End only counts as set", TrackMarker(0, 190_000).isSet)

    check("Set a start", TrackMarkers.withStart(none, 3_200, full).startMs == 3_200L)
    check("Start before 0 becomes 0", TrackMarkers.withStart(TrackMarker(3_000, 0), -500, full).startMs == 0L)
    check("Tiny start snaps to 'not set'", !TrackMarkers.withStart(none, 100, full).isSet)
    check("Start can't pass the end marker", TrackMarkers.withStart(TrackMarker(0, 100_000), 150_000, full).startMs == 99_000L, TrackMarkers.withStart(TrackMarker(0, 100_000), 150_000, full).startMs.toString())
    check("Start can't pass the end of the song", TrackMarkers.withStart(none, 999_999, full).startMs == 199_000L)

    check("Set an end", TrackMarkers.withEnd(none, 190_000, full).endMs == 190_000L)
    check("End can't go past the song", TrackMarkers.withEnd(TrackMarker(0, 100_000), 999_999, full).endMs == 0L, "snaps to 'not set'")
    check("End near the real end snaps to 'not set'", TrackMarkers.withEnd(none, 199_900, full).endMs == 0L)
    check("End can't come before the start marker", TrackMarkers.withEnd(TrackMarker(50_000, 0), 10_000, full).endMs == 51_000L)
    check("End 0 clears the end marker", TrackMarkers.withEnd(TrackMarker(0, 100_000), 0, full).endMs == 0L)
    check("Setting the end keeps the start", TrackMarkers.withEnd(TrackMarker(3_000, 0), 190_000, full).startMs == 3_000L)

    check("Clip length with both markers", TrackMarker(3_000, 190_000).clipLengthMs(full) == 187_000L)
    check("Clip length with start only", TrackMarker(3_000, 0).clipLengthMs(full) == 197_000L)
    check("Effective end falls back to the full length", TrackMarker(3_000, 0).effectiveEnd(full) == full)

    val saved = mapOf(42L to TrackMarker(3_000, 190_000), 7L to TrackMarker(0, 120_500), 9L to TrackMarker())
    val text = TrackMarkers.encode(saved)
    val back = TrackMarkers.decode(text)
    check("Saving drops songs with nothing set", back.size == 2 && 9L !in back, text)
    check("Save and load give the same markers", back[42L] == saved[42L] && back[7L] == saved[7L])
    check("Empty text loads as nothing", TrackMarkers.decode("").isEmpty())
    check("Broken entries are skipped, good ones kept", TrackMarkers.decode("abc;1,2;5,x,9;8,1000,0;;-3,5,5;4,-1,5").keys == setOf(8L))
    check("Whitespace is tolerated", TrackMarkers.decode(" 5 , 1000 , 0 ")[5L] == TrackMarker(1_000, 0))

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
