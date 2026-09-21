package space.subread.overlay.core

/**
 * Finds the line to show for a position in the media.
 *
 * The line stays on the screen until the next line starts, also in the silence after its end
 * time. A reader who looks a word up needs the line to stay; an empty overlay helps nobody.
 */
class CueIndex(cues: List<Cue>) {

    val cues: List<Cue> = cues.sortedBy { it.startMs }
    private val starts = LongArray(this.cues.size) { this.cues[it].startMs }

    val size: Int get() = cues.size

    /** The index of the last cue that starts at or before [positionMs]; -1 before the first cue. */
    fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = starts.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** The position at which another line comes on after [positionMs]; null after the last cue. */
    fun nextChangeAfter(positionMs: Long): Long? {
        val next = indexAt(positionMs) + 1
        return if (next < starts.size) starts[next] else null
    }
}
