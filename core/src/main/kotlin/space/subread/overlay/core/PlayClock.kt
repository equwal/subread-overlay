package space.subread.overlay.core

/**
 * What a media player said about its position, and when it said it.
 *
 * A player does not report each millisecond. It reports a position, the time of the report on
 * the clock of the device, and the speed. The position now is computed from these three.
 */
data class PlayClock(
    val positionMs: Long,
    /** The time of the report, on the same clock as the `nowMs` parameters. */
    val reportedAtMs: Long,
    val speed: Float,
    val playing: Boolean,
) {
    /** The position in the media at [nowMs]. */
    fun positionAt(nowMs: Long): Long =
        if (!playing) positionMs else positionMs + ((nowMs - reportedAtMs) * speed.toDouble()).toLong()

    /**
     * How long to wait, on the clock of the device, until the media is at [targetMs].
     * Null when the media does not move forward, or is already there.
     */
    fun waitUntil(targetMs: Long, nowMs: Long): Long? {
        if (!playing || speed <= 0f) return null
        val left = targetMs - positionAt(nowMs)
        if (left <= 0) return null
        return Math.ceil(left / speed.toDouble()).toLong()
    }
}
