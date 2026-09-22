package space.subread.overlay

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import space.subread.overlay.core.CueIndex
import space.subread.overlay.core.PlayClock

/**
 * Follows the media player that plays now, and says which subtitle line is on.
 *
 * Each Android media player publishes a media session: Voice, VLC, mpv, YouTube, a podcast app.
 * The session has the position, the time of that position and the speed. The follower computes
 * the position between two reports, and sleeps until the next line starts. It does not poll.
 *
 * A player that publishes no position: Android makes a position for it that starts at zero when
 * the player starts to play, and gives none while the player is paused. The follower then holds
 * the last position, so the line stays. The user sets the timing with the line buttons.
 */
class Follower(
    private val handler: Handler,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    /**
     * The index of the line that is on in [index] (-1 before the first line), true when there is
     * a player to follow, and true when that player plays.
     */
    private val onLine: (Int, Boolean, Boolean) -> Unit,
) {
    var index: CueIndex = CueIndex(emptyList())
        set(value) {
            field = value
            update()
        }

    /** Added to the position of the player, in milliseconds: a file with an intro, a player that is late. */
    var offsetMs: Long = 0
        set(value) {
            field = value
            update()
        }

    private var controller: MediaController? = null
    private var last: PlayClock? = null
    private val wake = Runnable { update() }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() = follow(emptyList())
    }

    /**
     * The sessions that are active, in the order of the system: the one that plays is first.
     * The follower keeps its player while that player is in the list and no other one plays.
     */
    fun follow(controllers: List<MediaController>) {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val kept = controllers.firstOrNull { it.sessionToken == controller?.sessionToken }
        val next = playing ?: kept ?: controllers.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            last = null
            next?.registerCallback(callback, handler)
        }
        update()
    }

    /** True while the player that is followed plays. */
    val playing: Boolean
        get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Pauses the player. True when it played. */
    fun pause(): Boolean {
        if (!playing) return false
        controller?.transportControls?.pause()
        return true
    }

    fun play() {
        controller?.transportControls?.play()
    }

    fun stop() {
        handler.removeCallbacks(wake)
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** Makes the line [steps] lines from the line that is on the current line, by a change of [offsetMs]. */
    fun shiftLines(steps: Int) {
        val clock = clock() ?: return
        if (index.size == 0) return
        val position = clock.positionAt(now())
        val target = (index.indexAt(position + offsetMs) + steps).coerceIn(0, index.size - 1)
        offsetMs = index.cues[target].startMs - position
    }

    private fun clock(): PlayClock? {
        val state = controller?.playbackState ?: return null
        val playing = state.state == PlaybackState.STATE_PLAYING
        val clock = if (state.position >= 0) {
            // Some players leave the time of the report at zero. Then the report is from now.
            val reportedAt = state.lastPositionUpdateTime.takeIf { it > 0 } ?: now()
            val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
            PlayClock(state.position, reportedAt, speed, playing)
        } else {
            // No position in this report: hold the position of the report before.
            PlayClock(last?.positionAt(now()) ?: 0, now(), 1f, playing)
        }
        return clock.also { last = it }
    }

    private fun update() {
        handler.removeCallbacks(wake)
        val clock = clock()
        if (clock == null) {
            onLine(-1, false, false)
            return
        }
        val position = clock.positionAt(now()) + offsetMs
        val at = index.indexAt(position)
        onLine(at, true, clock.playing)
        val next = index.nextChangeAfter(position) ?: return
        clock.waitUntil(next - offsetMs, now())?.let { handler.postDelayed(wake, it) }
    }
}
