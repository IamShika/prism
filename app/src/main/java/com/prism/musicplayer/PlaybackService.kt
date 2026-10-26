package com.prism.musicplayer

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.prism.musicplayer.audio.AudioEngine

/**
 * Keeps the music alive when the app is closed, and shows it in the phone's notification
 * and media controls (lock screen, quick settings, Bluetooth buttons, car displays...).
 *
 * The sound itself comes from [AudioEngine]; this service only publishes it to Android.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val engine = AudioEngine.get(this)

        // tapping the notification opens the app
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val newSession = MediaSession.Builder(this, engine.player)
            .setSessionActivity(openApp)
            .build()
        session = newSession
        addSession(newSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music playing, like most players. If nothing is playing, shut down.
        val player = AudioEngine.get(this).player
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            player.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Only the session is released. The player belongs to AudioEngine, which the app screen still uses.
        session?.release()
        session = null
        super.onDestroy()
    }
}
