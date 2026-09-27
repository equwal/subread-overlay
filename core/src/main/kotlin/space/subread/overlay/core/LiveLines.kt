package space.subread.overlay.core

/**
 * The lines that a caption app sends while it makes captions from live audio.
 *
 * A caption app sends a line many times while the speech goes on: a partial line, which grows.
 * When the sentence is finished, it sends the line once more as a final line. The panel shows the
 * newest line. Only a final line becomes the line before: a partial line goes away when the next
 * line comes, because the final line of the same speech takes its place.
 */
class LiveLines {

    /** The newest line. Empty before the first line. */
    var now: String = ""
        private set

    /** True when [now] is a partial line: the speech that it comes from goes on. */
    var partial: Boolean = false
        private set

    /** The last final line before [now]; null when there is none. */
    var before: String? = null
        private set

    private val finals = ArrayDeque<String>()

    /** The final lines before [now], the oldest first: the user scrolls back through them. At most [MAX_HISTORY]. */
    val history: List<String>
        get() = finals

    /** A new line from the caption app. */
    fun line(text: String, partial: Boolean) {
        if (!this.partial && now.isNotEmpty()) {
            before = now
            finals.addLast(now)
            if (finals.size > MAX_HISTORY) finals.removeFirst()
        }
        now = text
        this.partial = partial
    }

    /** Forgets each line: the caption app stopped. */
    fun clear() {
        now = ""
        partial = false
        before = null
        finals.clear()
    }

    /** The rows of the panel: the final lines, then the newest line. A partial line ends with [PARTIAL_MARK]. */
    fun rows(): List<String> = history + if (partial) now + PARTIAL_MARK else now

    companion object {
        /** The history keeps this many final lines. An hour of speech has fewer. */
        const val MAX_HISTORY = 1000

        /** The end of a partial line on the panel: the speech goes on. */
        const val PARTIAL_MARK = " …"

        /** A row of the panel without the mark of a partial line: the text of the line, for a card. */
        fun plain(row: String): String = row.removeSuffix(PARTIAL_MARK)
    }
}
