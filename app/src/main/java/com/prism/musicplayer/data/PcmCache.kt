package com.prism.musicplayer.data

import android.content.Context
import android.net.Uri
import com.prism.musicplayer.audio.PcmDecoder
import com.prism.musicplayer.audio.PcmFormat
import com.prism.musicplayer.audio.PcmSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** A decoded song, read straight from its file on the phone (the file is mapped into memory, so it doesn't fill the app's RAM). */
class MappedPcm(
    override val sampleRate: Int,
    override val frames: Int,
    private val samples: ShortBuffer,
) : PcmSource {
    override fun left(frame: Int): Float = samples.get(frame * 2) / 32768f
    override fun right(frame: Int): Float = samples.get(frame * 2 + 1) / 32768f
}

/**
 * Keeps decoded songs in the app's cache folder for the scratch engine. A 4-minute song takes about 40 MB,
 * so only the three most recent songs are kept; older ones are deleted. Android may also clear the cache when
 * the phone is short on space, in which case the song is simply decoded again.
 */
class PcmCache(context: Context) {

    private val appContext = context.applicationContext
    private val folder = File(appContext.cacheDir, "pcm").also { it.mkdirs() }

    // the length is part of the name, so a replaced file with the same id isn't mixed up with the old one
    private fun fileFor(songId: Long, durationMs: Long) = File(folder, "$songId-$durationMs.pcm")

    private class TooBig : Exception()

    /** The song, if it was decoded before. */
    fun open(songId: Long, durationMs: Long): MappedPcm? {
        val file = fileFor(songId, durationMs)
        if (!file.exists()) return null
        return try {
            val mapped = RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(PcmFormat.HEADER_BYTES)
                raf.readFully(head)
                val header = PcmFormat.parseHeader(head, raf.length())
                if (header == null) {
                    null
                } else {
                    val bytes = raf.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        PcmFormat.HEADER_BYTES.toLong(),
                        header.frames.toLong() * PcmFormat.BYTES_PER_FRAME,
                    ).order(ByteOrder.LITTLE_ENDIAN) // set before asShortBuffer, which copies the order
                    // the map stays valid after the file is closed
                    MappedPcm(header.sampleRate, header.frames, bytes.asShortBuffer())
                }
            }
            if (mapped == null) file.delete() else file.setLastModified(System.currentTimeMillis())
            mapped
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes the song into the cache and returns it. Takes a few seconds. Returns null if the song is too long,
     * can't be read, or the phone is out of space. Throws CancellationException if the job is cancelled.
     */
    suspend fun create(
        songId: Long,
        durationMs: Long,
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): MappedPcm? = withContext(Dispatchers.IO) {
        if (durationMs <= 0L || durationMs > MAX_DURATION_MS) return@withContext null
        val job = coroutineContext[Job]
        val target = fileFor(songId, durationMs)
        val part = File(folder, "${target.name}.${System.nanoTime()}.part")
        folder.listFiles()?.filter { it.name.endsWith(".part") && it != part }?.forEach { it.delete() }

        var frames = 0L
        var rate = 0
        try {
            var bytes = ByteArray(16384)
            BufferedOutputStream(FileOutputStream(part), 65536).use { out ->
                out.write(PcmFormat.header(0, 0)) // the real header is written when we know the length
                rate = PcmDecoder.decodeStereo(
                    context = appContext,
                    uri = uri,
                    expectedMs = durationMs,
                    onProgress = onProgress,
                    shouldStop = { job?.isActive == false },
                ) { stereo, count ->
                    val needed = count * PcmFormat.BYTES_PER_FRAME
                    if (bytes.size < needed) bytes = ByteArray(needed)
                    for (i in 0 until count * 2) {
                        val v = stereo[i].toInt()
                        bytes[i * 2] = v.toByte()
                        bytes[i * 2 + 1] = (v shr 8).toByte()
                    }
                    out.write(bytes, 0, needed)
                    frames += count
                    if (frames * PcmFormat.BYTES_PER_FRAME > MAX_BYTES) throw TooBig()
                }
            }
            if (job?.isActive == false) {
                part.delete()
                return@withContext null // the caller is cancelled; withContext throws for us
            }
            if (rate == 0 || frames <= 0L) {
                part.delete()
                return@withContext null
            }
            RandomAccessFile(part, "rw").use {
                it.seek(0)
                it.write(PcmFormat.header(rate, frames.toInt()))
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            part.delete() // too big, out of space, unreadable...
            return@withContext null
        }
        trim(keep = target)
        open(songId, durationMs)
    }

    /** Deletes the oldest songs, keeping the most recently used few (and always [keep]). */
    private fun trim(keep: File) {
        val files = folder.listFiles()?.filter { it.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        for ((index, file) in files.withIndex()) {
            if (index >= KEEP_SONGS && file != keep) file.delete()
        }
    }

    private companion object {
        const val KEEP_SONGS = 3
        const val MAX_DURATION_MS = 15 * 60 * 1000L
        const val MAX_BYTES = 200_000_000L
    }
}
