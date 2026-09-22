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

    /** A new line from the caption app. */
    fun line(text: String, partial: Boolean) {
        if (!this.partial && now.isNotEmpty()) before = now
        now = text
        this.partial = partial
    }

    /** Forgets each line: the caption app stopped. */
    fun clear() {
        now = ""
        partial = false
        before = null
    }
}
