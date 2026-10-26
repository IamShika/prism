package com.prism.musicplayer.data

/**
 * Rules for album folder names. No Android code in here, so it can be tested on a normal computer
 * (see tools/album-test/run.sh).
 */
object AlbumNaming {

    /** Albums are folders inside the phone's Music folder. */
    const val BASE_FOLDER = "Music"

    private const val MAX_LENGTH = 80
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")

    /**
     * Turns what the user typed into a safe folder name.
     * Characters Android doesn't allow in folder names become spaces; returns null if nothing usable is left.
     */
    fun sanitize(raw: String): String? {
        var s = raw.replace(illegal, " ").replace(spaces, " ").trim()
        s = s.trim('.', ' ') // a name made only of dots, or ending in a dot, causes trouble
        if (s.length > MAX_LENGTH) s = s.take(MAX_LENGTH).trim().trimEnd('.')
        return s.ifEmpty { null }
    }

    /** Where the album's folder is, in the form Android's MediaStore wants: "Music/Road Trip/". */
    fun relativePath(folder: String): String = "$BASE_FOLDER/$folder/"

    /**
     * If [fileName] is already taken in the folder, returns "name (1).ext", "name (2).ext" and so on.
     * Used on old Android versions where we move the files ourselves.
     */
    fun uniqueName(fileName: String, taken: Collection<String>): String {
        val lowered = taken.map { it.lowercase() }.toSet()
        if (fileName.lowercase() !in lowered) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate.lowercase() !in lowered) return candidate
            n++
        }
    }

    /** The message shown after a move, e.g. "Moved 5 songs to Music/Road Trip/". */
    fun summary(moved: Int, failed: Int, folder: String): String {
        val songs = if (moved == 1) "1 song" else "$moved songs"
        return when {
            moved > 0 && failed == 0 -> "Moved $songs to ${relativePath(folder)}"
            moved > 0 -> "Moved $songs to ${relativePath(folder)}. $failed couldn't be moved."
            else -> "Couldn't move the songs. Nothing was changed."
        }
    }
}
