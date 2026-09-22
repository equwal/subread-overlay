package space.subread.overlay.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {

    private fun stamp(ms: Long) =
        "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)

    private fun write(cues: List<Cue>) = cues.withIndex().joinToString("\n\n") { (i, c) ->
        "${i + 1}\n${stamp(c.startMs)} --> ${stamp(c.endMs)}\n${c.text}"
    }

    /** Cues that do not share a start time, so that the sorted order is one order only. */
    private val cues = Arb.list(
        Arb.int(1..20_000).map { it.toLong() },
        0..40,
    ).map { gaps ->
        var at = 0L
        gaps.mapIndexed { i, gap ->
            at += gap
            Cue(at, at + 1 + gap, "line $i\nsecond row")
        }
    }

    @Test
    fun whatIsWrittenIsReadBack(): Unit = runBlocking {
        checkAll(cues) { list ->
            assertEquals(list, Srt.parse(write(list)))
            assertEquals(list, Srt.parse("\uFEFF" + write(list).replace("\n", "\r\n")))
        }
    }

    /** Lines of the languages the overlay is checked against, with the marks their books use. */
    private val lines = Arb.element(
        "It was a dark night; the rain fell.",                    // en
        "— Não me parece bonito — disse ela, à porta.",           // pt
        "¿Qué es esto? ¡Ñandú, señor Quijote!",                   // es
        "«Ёлка, — сказал он, — и её огни».",                      // ru
        "吾輩は猫である。名前はまだ無い。",                          // ja
        "「女のいない男たち」　東京で暮らしている",                    // ja, ideographic space and brackets
    )

    private val multilingualCues = Arb.list(Arb.pair(Arb.int(1..20_000).map { it.toLong() }, lines), 1..30).map { list ->
        var at = 0L
        list.map { (gap, text) ->
            at += gap
            Cue(at, at + 1 + gap, text)
        }
    }

    @Test
    fun aLineOfAnyScriptIsReadBackUnchanged(): Unit = runBlocking {
        checkAll(multilingualCues) { list ->
            assertEquals(list, Srt.parse(write(list)))
        }
    }

    @Test
    fun noTextMakesTheReaderFail(): Unit = runBlocking {
        checkAll(Arb.stringPattern("[0-9a-z:>,\\- \n]{0,200}")) { junk -> Srt.parse(junk) }
    }

    @Test
    fun aBrokenBlockIsSkippedAndTheRestIsKept() {
        val text = """
            1
            00:00:01.5 --> 00:00:02,000
            <i>first</i>

            this is not a cue

            3
            00:00:05,000 --> 00:00:04,000
            ends before it starts

            00:00:09,000 --> 00:00:10,000
            {\an8}no number

            1:00:00,000 --> 100:00:00,000
            ｛Japanese　女のいない男たち｝
        """.trimIndent()
        assertEquals(
            listOf(
                Cue(1_500, 2_000, "first"),
                Cue(9_000, 10_000, "no number"),
                Cue(3_600_000, 360_000_000, "｛Japanese　女のいない男たち｝"),
            ),
            Srt.parse(text),
        )
    }
}

class CueIndexTest {

    private val starts = Arb.list(Arb.long(0L..100_000L), 0..60)

    @Test
    fun theSameAnswerAsALookAtEachCue(): Unit = runBlocking {
        checkAll(starts, Arb.long(-10L..100_010L)) { list, position ->
            val index = CueIndex(list.map { Cue(it, it + 500, "x") })
            val sorted = list.sorted()
            assertEquals(sorted.indexOfLast { it <= position }, index.indexAt(position))
            assertEquals(sorted.firstOrNull { it > position }, index.nextChangeAfter(position))
        }
    }

    @Test
    fun theLineStaysUntilTheNextLineStarts() {
        val index = CueIndex(listOf(Cue(1_000, 2_000, "a"), Cue(10_000, 11_000, "b")))
        assertEquals(-1, index.indexAt(999))
        assertEquals(0, index.indexAt(1_000))
        assertEquals("the silence after the end of the line", 0, index.indexAt(9_999))
        assertEquals(1, index.indexAt(10_000))
        assertEquals(1, index.indexAt(Long.MAX_VALUE))
        assertNull(index.nextChangeAfter(10_000))
        assertEquals(-1, CueIndex(emptyList()).indexAt(5))
    }
}

class PlayClockTest {

    @Test
    fun aPausedPlayerDoesNotMove(): Unit = runBlocking {
        checkAll(Arb.long(0L..1_000_000L), Arb.long(0L..1_000_000L)) { position, later ->
            val clock = PlayClock(position, 0, 1f, playing = false)
            assertEquals(position, clock.positionAt(later))
            assertNull(clock.waitUntil(position + 1, later))
        }
    }

    @Test
    fun afterTheWaitTheMediaIsAtTheTarget(): Unit = runBlocking {
        checkAll(
            Arb.long(0L..36_000_000L),
            Arb.long(0L..10_000_000L),
            Arb.float(0.25f..4f),
            Arb.long(1L..600_000L),
            Arb.boolean(),
        ) { position, now, speed, ahead, reportedLate ->
            val clock = PlayClock(position, if (reportedLate) now else 0, speed, playing = true)
            val target = clock.positionAt(now) + ahead
            val wait = clock.waitUntil(target, now)!!
            // Not early, and late by less than one step of the device clock at this speed.
            assertTrue("early", clock.positionAt(now + wait) >= target - 1)
            assertTrue("late", clock.positionAt(now + wait) <= target + speed.toLong() + 1)
        }
    }

    @Test
    fun speedScalesTheTime() {
        val clock = PlayClock(60_000, reportedAtMs = 1_000, speed = 2f, playing = true)
        assertEquals(60_000, clock.positionAt(1_000))
        assertEquals(64_000, clock.positionAt(3_000))
        assertEquals(500L, clock.waitUntil(65_000, 3_000))
        assertNull("already past", clock.waitUntil(63_000, 3_000))
    }
}
