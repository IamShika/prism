package com.prism.musicplayer.data

import android.content.Context
import com.prism.musicplayer.audio.BpmStore

/** Remembers each song's tempo on the phone, so it only has to be measured once. */
class BpmRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("prism_bpm", Context.MODE_PRIVATE)
    private val bpms = BpmStore.decode(prefs.getString(KEY, "") ?: "").toMutableMap()

    fun all(): Map<Long, Float> = bpms.toMap()

    fun set(songId: Long, bpm: Float?) {
        if (bpm == null) bpms.remove(songId) else bpms[songId] = bpm
        prefs.edit().putString(KEY, BpmStore.encode(bpms)).apply()
    }

    companion object {
        private const val KEY = "bpm"

        @Volatile
        private var instance: BpmRepository? = null

        fun get(context: Context): BpmRepository =
            instance ?: synchronized(this) {
                instance ?: BpmRepository(context.applicationContext).also { instance = it }
            }
    }
}
