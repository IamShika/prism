package com.prism.musicplayer.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Carries out what [CrossfadeLogic] decides, on two real players:
 * [main] (the one you normally hear, and the one the notification controls) and
 * [ghost] (a helper that plays the end of the old song during a blend).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CrossfadeController(private val main: ExoPlayer, private val ghost: ExoPlayer) {

    private val logic = CrossfadeLogic()
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { tick() }

    /** Skips that are waiting for the ghost to warm up. */
    private val pending = mutableListOf<() -> Unit>()

    var settings: CrossfadeSettings
        get() = logic.settings
        set(value) {
            logic.settings = value
            restartLoop()
        }

    init {
        main.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                ghost.playbackParameters = playbackParameters
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // pausing while the ghost is rolling or blending pauses both players
                val phase = logic.phase
                if (phase == CrossfadeLogic.Phase.FADING || phase == CrossfadeLogic.Phase.ROLLING) {
                    if (playWhenReady) ghost.play() else ghost.pause()
                }
            }
        })
    }

    /**
     * Wraps a user skip. If crossfade wants to blend, the skip is delayed by a fraction of a second
     * until the ghost is ready. Otherwise it happens immediately.
     */
    fun manualSkip(willChangeSong: Boolean, action: () -> Unit) {
        if (!willChangeSong) {
            action()
            return
        }
        val result = logic.onManualSkip(snapshot())
        result.actions.forEach(::apply)
        if (result.deferred) {
            pending += action
            restartLoop()
        } else {
            action()
        }
    }

    private fun restartLoop() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun tick() {
        logic.tick(snapshot()).forEach(::apply)
        // when crossfade is off the loop sleeps until it is switched on or a skip arrives
        if (logic.settings.fadeMs > 0L) handler.postDelayed(tickRunnable, logic.nextDelayMs())
    }

    private fun snapshot(): CrossfadeLogic.Snapshot {
        val hasNext = main.hasNextMediaItem()
        val next = if (hasNext) main.getMediaItemAt(main.nextMediaItemIndex) else null
        val duration = main.duration
        return CrossfadeLogic.Snapshot(
            nowMs = SystemClock.elapsedRealtime(),
            itemId = main.currentMediaItem?.mediaId,
            hasNext = hasNext,
            nextItemId = next?.mediaId,
            repeatOff = main.repeatMode == Player.REPEAT_MODE_OFF,
            isPlaying = main.isPlaying,
            playWhenReady = main.playWhenReady,
            durationMs = if (duration == C.TIME_UNSET) 0L else duration,
            positionMs = main.currentPosition,
            speed = main.playbackParameters.speed,
            ghostReady = ghost.playbackState == Player.STATE_READY,
            ghostPlaying = ghost.isPlaying,
            ghostPositionMs = ghost.currentPosition,
        )
    }

    private fun apply(action: CrossfadeLogic.Action) {
        when (action) {
            is CrossfadeLogic.Action.Prepare -> {
                val item = main.currentMediaItem ?: return
                ghost.volume = 0f
                ghost.playbackParameters = main.playbackParameters
                ghost.setMediaItem(item, action.startPositionMs)
                ghost.playWhenReady = false
                ghost.prepare()
            }

            is CrossfadeLogic.Action.StartGhost -> {
                // Start the old song on the ghost, silently. It warms up here, while main is
                // still playing the same song out loud, so nobody hears the warm-up.
                ghost.volume = 0f
                ghost.seekTo(action.seekToMs)
                ghost.play()
            }

            is CrossfadeLogic.Action.SeekGhost -> ghost.seekTo(action.toMs)

            is CrossfadeLogic.Action.Start -> {
                // The handover. The ghost is already playing, level with main, so only the volumes change.
                main.volume = 0f
                ghost.volume = 1f
                if (!ghost.playWhenReady) ghost.play() // safety net, normally it is playing already
                if (action.manual) runPending() else main.seekToNextMediaItem()
            }

            is CrossfadeLogic.Action.Volumes -> {
                main.volume = action.main
                ghost.volume = action.ghost
            }

            CrossfadeLogic.Action.Release -> {
                ghost.stop()
                ghost.clearMediaItems()
                main.volume = 1f
            }

            CrossfadeLogic.Action.RunPending -> runPending()
        }
    }

    private fun runPending() {
        val toRun = pending.toList()
        pending.clear()
        toRun.forEach { it() }
    }
}

/**
 * The player that the screen and the notification talk to. It behaves exactly like the normal player,
 * except that "next", "previous" and "play this other song" go through the crossfade first.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CrossfadePlayer(
    private val exo: ExoPlayer,
    private val crossfade: CrossfadeController,
) : ForwardingPlayer(exo) {

    override fun seekToNext() {
        crossfade.manualSkip(exo.hasNextMediaItem()) { exo.seekToNext() }
    }

    override fun seekToNextMediaItem() {
        crossfade.manualSkip(exo.hasNextMediaItem()) { exo.seekToNextMediaItem() }
    }

    override fun seekToPrevious() {
        // after 3 seconds "previous" only restarts the song, which needs no blend
        val goesToPreviousSong = exo.hasPreviousMediaItem() && exo.currentPosition <= exo.maxSeekToPreviousPosition
        crossfade.manualSkip(goesToPreviousSong) { exo.seekToPrevious() }
    }

    override fun seekToPreviousMediaItem() {
        crossfade.manualSkip(exo.hasPreviousMediaItem()) { exo.seekToPreviousMediaItem() }
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        // tapping another song in the list
        val current = exo.currentMediaItem?.mediaId
        val target = mediaItems.getOrNull(startIndex)?.mediaId
        val changesSong = current != null && target != null && current != target
        crossfade.manualSkip(changesSong) { exo.setMediaItems(mediaItems, startIndex, startPositionMs) }
    }
}
