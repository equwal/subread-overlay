package space.subread.overlay.core

/** One subtitle line. The times are milliseconds on the clock of the media file. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Reads SubRip (`.srt`) text.
 *
 * The reader is lenient: a block without a valid time line is skipped and the rest of the file
 * is kept. Subtitle files from the wild have stray numbers, comments and broken blocks.
 */
object Srt {

    private val TIMES = Regex(
        """(\d{1,3}):(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[.,](\d{1,3})""",
    )
    private val TAG = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /**
     * The cues of [content], sorted by start time.
     *
     * Accepts a byte order mark, CRLF, CR or LF line ends, and `.` or `,` before the milliseconds.
     * Removes format tags (`<i>`, `{\an8}`): the overlay shows plain text.
     */
    fun parse(content: String): List<Cue> =
        content.removePrefix("\uFEFF")
            .replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n"))
            .mapNotNull(::block)
            .sortedBy { it.startMs }

    private fun block(block: String): Cue? {
        val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = lines.indexOfFirst { TIMES.containsMatchIn(it) }
        if (at == -1) return null
        val g = TIMES.find(lines[at])!!.groupValues
        val start = millis(g[1], g[2], g[3], g[4])
        val end = millis(g[5], g[6], g[7], g[8])
        val text = lines.drop(at + 1).joinToString("\n") { it.replace(TAG, "") }.trim()
        if (end <= start || text.isEmpty()) return null
        return Cue(start, end, text)
    }

    private fun millis(h: String, m: String, s: String, fraction: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 +
            fraction.padEnd(3, '0').take(3).toLong()
}
