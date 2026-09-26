package space.subread.overlay.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    /** The rows are each line up to the line of now, plus the next line with the lines around. */
    @Test
    fun theRowsEndAtTheLineOfNowOrTheLineAfterIt(): Unit = runBlocking {
        checkAll(Arb.int(0..50), Arb.int(-1..60), Arb.boolean()) { lines, now, around ->
            val rows = Transcript.rowCount(lines, now, around)
            assertTrue("$rows rows of $lines lines", rows in 0..lines)
            if (now < 0 || lines == 0) {
                assertEquals(0, rows)
            } else {
                val at = minOf(now, lines - 1)
                val expected = if (around && at + 1 < lines) at + 2 else at + 1
                assertEquals(expected, rows)
            }
        }
    }

    /** The rows of the height hold the line of now, stay in the list, and are 1 row, or 3 rows at most with the lines around. */
    @Test
    fun theRowsOfTheHeightHoldTheLineOfNow(): Unit = runBlocking {
        checkAll(Arb.int(1..50), Arb.int(0..60), Arb.boolean()) { lines, now, around ->
            val rows = Transcript.rowCount(lines, now, around)
            val follow = Transcript.followRows(rows, now, around)
            val at = minOf(now, lines - 1)
            assertTrue("$follow has $at", at in follow)
            assertTrue("$follow in the rows", follow.first >= 0 && follow.last < rows)
            if (around) assertTrue(follow.count() in 1..3) else assertEquals(1, follow.count())
        }
    }

    /** The bottom edge stays when the panel grows, unless the top reaches the top of the screen. */
    @Test
    fun theBottomEdgeStaysWhenThePanelGrows(): Unit = runBlocking {
        checkAll(Arb.int(0..3000), Arb.int(-100..3000)) { top, extra ->
            val grown = Transcript.grownTop(top, extra)
            assertTrue(grown in 0..top)
            if (extra in 0..top) assertEquals(top - extra, grown)
            if (extra > top) assertEquals(0, grown)
            if (extra <= 0) assertEquals(top, grown)
        }
    }

    @Test
    fun aFilmOfThreeLines() {
        assertEquals(0, Transcript.rowCount(3, -1, around = true))
        assertEquals(1, Transcript.rowCount(3, 0, around = false))
        assertEquals(2, Transcript.rowCount(3, 0, around = true))
        assertEquals(3, Transcript.rowCount(3, 2, around = true))
        assertEquals(0..1, Transcript.followRows(2, 0, around = true))
        assertEquals(1..2, Transcript.followRows(3, 2, around = true))
        assertEquals(2..2, Transcript.followRows(3, 2, around = false))
        assertEquals(IntRange.EMPTY, Transcript.followRows(0, 0, around = true))
    }
}
