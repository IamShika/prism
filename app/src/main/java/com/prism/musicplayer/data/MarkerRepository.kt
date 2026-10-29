package com.prism.musicplayer.data

import android.content.Context

/** Remembers each song's start/end markers on the phone. One shared copy for the whole app. */
class MarkerRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("prism_markers", Context.MODE_PRIVATE)
    private val markers = TrackMarkers.decode(prefs.getString(KEY, "") ?: "").toMutableMap()

    fun get(songId: Long): TrackMarker = markers[songId] ?: TrackMarker()

    fun set(songId: Long, marker: TrackMarker) {
        if (marker.isSet) markers[songId] = marker else markers.remove(songId)
        prefs.edit().putString(KEY, TrackMarkers.encode(markers)).apply()
    }

    companion object {
        private const val KEY = "markers"

        @Volatile
        private var instance: MarkerRepository? = null

        fun get(context: Context): MarkerRepository =
            instance ?: synchronized(this) {
                instance ?: MarkerRepository(context.applicationContext).also { instance = it }
            }
    }
}
