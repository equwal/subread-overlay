package space.subread.overlay.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LiveLinesTest {

    /** Lines as a caption app sends them: the text, and true for a partial line. */
    private val feeds = Arb.list(Arb.pair(Arb.stringPattern("[a-zあ-ん ]{1,12}"), Arb.boolean()), 0..40)

    /** The line before is the last final line that came before the newest line. */
    @Test
    fun theLineBeforeIsTheLastFinalLineBeforeTheNewestLine(): Unit = runBlocking {
        checkAll(feeds) { feed ->
            val lines = LiveLines()
            feed.forEach { (text, partial) -> lines.line(text, partial) }
            val last = feed.lastOrNull()
            assertEquals(last?.first.orEmpty(), lines.now)
            assertEquals(last?.second == true, lines.partial)
            val finalsBefore = feed.dropLast(1).filter { !it.second }
            assertEquals(finalsBefore.lastOrNull()?.first, lines.before)
        }
    }

    @Test
    fun aPartialLineGrowsAndThenItsFinalLineTakesItsPlace() {
        val lines = LiveLines()
        lines.line("It was", partial = true)
        lines.line("It was a dark", partial = true)
        assertEquals("It was a dark", lines.now)
        assertNull(lines.before)
        lines.line("It was a dark night.", partial = false)
        assertEquals("It was a dark night.", lines.now)
        assertFalse(lines.partial)
        assertNull("a partial line is not a line before", lines.before)
        lines.line("The rain", partial = true)
        assertEquals("It was a dark night.", lines.before)
    }

    @Test
    fun clearForgetsEachLine() {
        val lines = LiveLines()
        lines.line("one", partial = false)
        lines.line("two", partial = true)
        lines.clear()
        assertEquals("", lines.now)
        assertFalse(lines.partial)
        assertNull(lines.before)
    }
}
