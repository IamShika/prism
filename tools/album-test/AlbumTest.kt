// Offline checks for the album naming rules. Runs on a normal computer, no phone needed.
// See tools/album-test/run.sh
import com.prism.musicplayer.data.AlbumNaming

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "  [$detail]" else ""))
    if (!ok) failures++
}

fun main() {
    println("Album naming tests\n")

    check("Normal name stays as it is", AlbumNaming.sanitize("Road Trip") == "Road Trip")
    check("Spaces around the name are removed", AlbumNaming.sanitize("   Road Trip  ") == "Road Trip")
    check("Double spaces are squeezed", AlbumNaming.sanitize("Road    Trip") == "Road Trip")
    check("Slashes can't create sub-folders", AlbumNaming.sanitize("a/b\\c") == "a b c", AlbumNaming.sanitize("a/b\\c").toString())
    check("Characters Android forbids are replaced", AlbumNaming.sanitize("Hits: the *best*? <2020> \"pop\" |mix|") == "Hits the best 2020 pop mix")
    check("'..' can't escape the Music folder", AlbumNaming.sanitize("..") == null && AlbumNaming.sanitize("../..") == null)
    check("A single dot is refused", AlbumNaming.sanitize(".") == null)
    check("Empty and blank names are refused", AlbumNaming.sanitize("") == null && AlbumNaming.sanitize("   ") == null)
    check("Only forbidden characters is refused", AlbumNaming.sanitize("???") == null)
    check("Trailing dots are removed", AlbumNaming.sanitize("Chill...") == "Chill")
    check("Dots inside the name are fine", AlbumNaming.sanitize("Vol. 2") == "Vol. 2")
    check("Control characters are removed", AlbumNaming.sanitize("Line\none\ttwo") == "Line one two")
    check("Emoji and other languages are kept", AlbumNaming.sanitize("ເພງ 🎵 日本語") == "ເພງ 🎵 日本語")
    val long = AlbumNaming.sanitize("x".repeat(300))
    check("Very long names are cut to 80 characters", long != null && long.length == 80)

    check("Folder path has the right shape", AlbumNaming.relativePath("Road Trip") == "Music/Road Trip/")

    check("Unique name: free name unchanged", AlbumNaming.uniqueName("a.mp3", listOf("b.mp3")) == "a.mp3")
    check("Unique name: taken name gets (1)", AlbumNaming.uniqueName("a.mp3", listOf("a.mp3")) == "a (1).mp3")
    check("Unique name: keeps counting", AlbumNaming.uniqueName("a.mp3", listOf("a.mp3", "a (1).mp3")) == "a (2).mp3")
    check("Unique name: capital letters count as the same", AlbumNaming.uniqueName("a.mp3", listOf("A.MP3")) == "a (1).mp3")
    check("Unique name: file without extension", AlbumNaming.uniqueName("track", listOf("track")) == "track (1)")

    check("Message: all moved", AlbumNaming.summary(5, 0, "Trip") == "Moved 5 songs to Music/Trip/")
    check("Message: one song", AlbumNaming.summary(1, 0, "Trip") == "Moved 1 song to Music/Trip/")
    check("Message: some failed", AlbumNaming.summary(3, 2, "Trip").contains("2 couldn't be moved"))
    check("Message: all failed", AlbumNaming.summary(0, 4, "Trip").startsWith("Couldn't move"))

    println()
    println(if (failures == 0) "ALL TESTS PASSED" else "$failures TEST(S) FAILED")
    if (failures != 0) System.exit(1)
}
