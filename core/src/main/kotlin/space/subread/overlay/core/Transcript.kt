package space.subread.overlay.core

/**
 * The rows of the panel. The panel shows the lines as a list that the user can scroll back:
 * each line from the first line up to the line of now, and the next line too when the lines
 * around are on.
 */
object Transcript {

    /**
     * The number of rows for [lines] lines, with the line of now at [now]. A [now] of -1 is
     * before the first line: no row. With [around], the row after the line of now is there too.
     */
    fun rowCount(lines: Int, now: Int, around: Boolean): Int {
        if (lines <= 0 || now < 0) return 0
        val last = now.coerceAtMost(lines - 1)
        return (last + if (around) 2 else 1).coerceAtMost(lines)
    }

    /**
     * The rows that the panel shows while it follows the player: the line of now, and with
     * [around] the line before and the line after it. The height of these rows is the height of
     * the panel, so the panel does not cover more of the player than the line needs.
     */
    fun followRows(rows: Int, now: Int, around: Boolean): IntRange {
        if (rows <= 0 || now < 0) return IntRange.EMPTY
        val at = now.coerceAtMost(rows - 1)
        return if (around) (at - 1).coerceAtLeast(0)..(at + 1).coerceAtMost(rows - 1) else at..at
    }

    /**
     * The top of the panel after it grows by [extra] pixels. The bottom edge stays where it is,
     * so the top goes up. The top does not go above the screen: then the panel grows down.
     */
    fun grownTop(top: Int, extra: Int): Int = (top - extra.coerceAtLeast(0)).coerceAtLeast(0)
}
