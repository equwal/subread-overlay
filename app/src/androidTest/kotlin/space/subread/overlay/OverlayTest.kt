package space.subread.overlay

import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.overlay.core.Cue
import space.subread.overlay.core.CueIndex
import java.util.concurrent.CopyOnWriteArrayList

/** The word cutter of the device. It is ICU of Android, so the test runs on the device. */
@RunWith(AndroidJUnit4::class)
class WordsTest {

    private fun word(text: String, offset: Int) = Words.at(text, offset)?.let { text.substring(it.first, it.last + 1) }

    @Test
    fun aWordOfALanguageWithSpaces() {
        val text = "Он вышел из дома, and never came back."
        assertEquals("вышел", word(text, text.indexOf("шел")))
        assertEquals("never", word(text, text.indexOf("never")))
        assertNull("a space", word(text, 2))
        assertNull("a comma", word(text, text.indexOf(',')))
        assertNull(word(text, text.length))
        assertNull(word("", 0))
    }

    @Test
    fun aWordWithAccentsOrInvertedMarks() {
        val pt = "— Não me parece bonito — disse ela, à porta."
        assertEquals("Não", word(pt, pt.indexOf("Não")))
        assertEquals("à", word(pt, pt.indexOf("à")))
        assertNull("a dash", word(pt, 0))
        val es = "¿Qué es esto? ¡Ñandú, señor!"
        assertEquals("Qué", word(es, es.indexOf("Qué")))
        assertEquals("Ñandú", word(es, es.indexOf("Ñandú")))
        assertNull("the inverted mark", word(es, 0))
        val ru = "«Ёлка, — сказал он, — и её огни»."
        assertEquals("Ёлка", word(ru, ru.indexOf("Ёлка")))
        assertEquals("её", word(ru, ru.indexOf("её")))
    }

    @Test
    fun aWordOfJapaneseThatHasNoSpaces() {
        val text = "女のいない男たちは、東京で暮らしている。"
        // The dictionary of ICU decides the cut. The test asks for what each good cut has:
        // a word is shorter than the sentence, stops at the comma, and has the character under the finger.
        for (at in listOf(0, text.indexOf("男"), text.indexOf("東京"), text.indexOf("暮"))) {
            val range = Words.at(text, at)!!
            assertTrue("$range has $at", at in range)
            assertTrue("$range is a word, not the sentence", range.last - range.first < 6)
            assertTrue("no comma in a word", '、' !in text.substring(range.first, range.last + 1))
        }
        assertEquals("東京", word(text, text.indexOf("東京")))
        assertNull(word(text, text.indexOf('、')))
    }

    @Test
    fun aDragSelectsFromTheFirstWordToTheWordUnderTheFinger() {
        val text = "one two three four"
        val anchor = Words.at(text, text.indexOf("two"))!!
        assertEquals("two three", Words.between(text, anchor, text.indexOf("three")).let { text.substring(it.first, it.last + 1) })
        assertEquals("one two", Words.between(text, anchor, 0).let { text.substring(it.first, it.last + 1) })
        assertEquals("a drag over a space keeps the selection", anchor, Words.between(text, anchor, 3))
    }
}

/** The panel, in the window of an activity: the test needs no permission that way. */
@RunWith(AndroidJUnit4::class)
class OverlayViewTest {

    private val lookedUp = CopyOnWriteArrayList<String>()
    private val shared = CopyOnWriteArrayList<String>()
    private val events = object : OverlayView.Events {
        override fun onDrag(dx: Float, dy: Float) = Unit
        override fun onDragEnd() = Unit
        override fun onClose() = Unit
        override fun onLookUp(word: String) { lookedUp += word }
        override fun onShare(word: String) { shared += word }
        override fun onTogglePlay() = Unit
        override fun onShiftLines(steps: Int) = Unit
        override fun onNudge(ms: Long) = Unit
    }

    /** The x of the middle of the character at [offset], in the panel. */
    private fun OverlayView.middleOf(offset: Int): Float {
        val layout = text.layout
        return text.totalPaddingLeft + (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2
    }

    /** A y just over the baseline of the row of the character at [offset], in the panel. */
    private fun OverlayView.rowOf(offset: Int): Float =
        text.totalPaddingTop + text.layout.getLineBaseline(text.layout.getLineForOffset(offset)) - 5f

    @Test
    fun aTapSelectsTheWordUnderTheFingerAndLookUpSendsIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var panel: OverlayView
            scenario.onActivity {
                panel = OverlayView(it, events)
                panel.setTextSize(30f)
                it.addContentView(panel, ViewGroup.LayoutParams(-1, -2))
                panel.showLine("reading along is easy")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val start = panel.line.indexOf("along")
                // The middle of the third letter of "along".
                panel.selectAt(panel.middleOf(start + 2), panel.rowOf(start + 2))
                assertEquals(start..start + 4, panel.selection)

                // A drag to the last word selects the three words.
                val last = panel.line.lastIndex
                panel.selectAt(panel.middleOf(last), panel.rowOf(last), extend = true)
                assertEquals(start..last, panel.selection)

                val found = arrayListOf<android.view.View>()
                panel.findViewsWithText(found, "Look up", android.view.View.FIND_VIEWS_WITH_TEXT)
                found.single().performClick()
                assertEquals(listOf("along is easy"), lookedUp)
                found.clear()
                panel.findViewsWithText(found, "Share", android.view.View.FIND_VIEWS_WITH_TEXT)
                found.single().performClick()
                assertEquals(listOf("along is easy"), shared)

                // The next line has no selection from the line before.
                panel.showLine("the next line")
                assertNull(panel.selection)
            }
        }
    }

    @Test
    fun theLinesAroundAreOnTheirOwnRowsAndAWordOfThemCanBeSelected() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var panel: OverlayView
            scenario.onActivity {
                panel = OverlayView(it, events)
                panel.setTextSize(30f)
                it.addContentView(panel, ViewGroup.LayoutParams(-1, -2))
                panel.showLine("the line of now", "the line before", "the line after")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals("the line before\nthe line of now\nthe line after", panel.line)
                val layout = panel.text.layout
                val now = panel.line.indexOf("the line of now")
                val start = panel.line.indexOf("after")
                assertTrue("each line on its own row", layout.getLineForOffset(0) < layout.getLineForOffset(now))
                assertTrue("each line on its own row", layout.getLineForOffset(now) < layout.getLineForOffset(start))
                panel.selectAt(panel.middleOf(start + 1), panel.rowOf(start + 1))
                assertEquals(start..start + 4, panel.selection)

                // The same lines again change nothing; one line alone is one row again.
                panel.showLine("the line of now", "the line before", "the line after")
                assertEquals(start..start + 4, panel.selection)
                panel.showLine("the line of now")
                assertEquals("the line of now", panel.line)
                assertNull(panel.selection)
            }
        }
    }

    @Test
    fun theTransparencyIsTheAlphaOfTheBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val panel = OverlayView(it, events)
                panel.setTransparency(40)
                assertEquals(153, panel.background.alpha)
                panel.setTransparency(0)
                assertEquals(255, panel.background.alpha)
            }
        }
    }
}

/** A media session of the test plays the part of Voice, VLC or YouTube. */
@RunWith(AndroidJUnit4::class)
class FollowerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private val lines = CopyOnWriteArrayList<String?>()
    private val index = CueIndex(listOf(Cue(1_000, 2_000, "one"), Cue(60_000, 61_000, "two"), Cue(60_400, 61_000, "three")))

    private fun state(state: Int, position: Long, speed: Float = 1f) = PlaybackState.Builder()
        .setState(state, position, speed, SystemClock.elapsedRealtime()).build()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun waitFor(line: String?) {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (lines.lastOrNull() != line && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
        assertEquals(lines.toString(), line, lines.lastOrNull())
    }

    @Test
    fun theLineFollowsThePlayerThroughPauseSeekSpeedAndOffset() {
        val session = MediaSession(context, "test player")
        lateinit var follower: Follower
        try {
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 1_500))
            session.isActive = true
            onMain {
                follower = Follower(main) { at, _, _ -> lines += follower.index.cues.getOrNull(at)?.text }
                follower.index = index
                follower.follow(listOf(MediaController(context, session.sessionToken)))
            }
            waitFor("one")

            // A seek. The player is paused: the line changes at once and then stays.
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 500))
            waitFor(null)

            // Play at double speed from 59.6 s: "two" is 0.2 s away, "three" 0.2 s after it.
            val started = SystemClock.elapsedRealtime()
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 59_600, speed = 2f))
            waitFor("one")
            waitFor("two")
            waitFor("three")
            val took = SystemClock.elapsedRealtime() - started
            assertTrue("the follower waited $took ms; at double speed 0.8 s of media is 0.4 s", took in 350..1_500)

            // The subtitles are 60 s late for this media: the user shifts them.
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 100))
            waitFor(null)
            onMain { follower.offsetMs = 60_000 }
            waitFor("two")

            // "line ▶" makes the next line the line of now.
            onMain { follower.shiftLines(1) }
            waitFor("three")
            onMain { assertEquals(60_400L - 100L, follower.offsetMs) }
        } finally {
            onMain { follower.stop() }
            session.release()
        }
    }

    @Test
    fun aPlayerWithoutAPositionKeepsItsLineWhenItPauses() {
        val session = MediaSession(context, "player without position")
        lateinit var follower: Follower
        try {
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN))
            session.isActive = true
            onMain {
                follower = Follower(main) { at, _, _ -> lines += follower.index.cues.getOrNull(at)?.text }
                follower.index = CueIndex(listOf(Cue(0, 300, "first"), Cue(400, 900, "second")))
                follower.follow(listOf(MediaController(context, session.sessionToken)))
            }
            // Android counts from zero for a player that plays and gives no position.
            waitFor("second")
            // In a pause there is no position at all. The first version went back to "before the first line".
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN))
            Thread.sleep(500)
            assertEquals(lines.toString(), "second", lines.last())
        } finally {
            onMain { follower.stop() }
            session.release()
        }
    }

    @Test
    fun noPlayerIsSaidSo() {
        val players = CopyOnWriteArrayList<Boolean>()
        onMain {
            val follower = Follower(main) { _, hasPlayer, _ -> players += hasPlayer }
            follower.follow(emptyList())
        }
        assertEquals(false, players.last())
    }

    @Test
    fun aLookupPausesThePlayerAndTheButtonStartsItAgain() {
        val session = MediaSession(context, "test player")
        val playing = CopyOnWriteArrayList<Boolean>()
        var plays = 0
        var pauses = 0
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { plays++ }
            override fun onPause() { pauses++ }
        }, main)
        lateinit var follower: Follower
        try {
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 1_500))
            session.isActive = true
            onMain {
                follower = Follower(main) { _, _, isPlaying -> playing += isPlaying }
                follower.index = index
                follower.follow(listOf(MediaController(context, session.sessionToken)))
            }
            val until = SystemClock.elapsedRealtime() + 5_000
            while (playing.lastOrNull() != true && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            assertEquals(true, playing.last())

            onMain { assertTrue("a player that plays is paused", follower.pause()) }
            session.setPlaybackState(state(PlaybackState.STATE_PAUSED, 1_500))
            while (playing.lastOrNull() != false && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            assertEquals(false, playing.last())
            onMain { assertEquals("a paused player is not paused again", false, follower.pause()) }
            onMain { follower.play() }
            while ((plays < 1 || pauses < 1) && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            assertEquals(1, pauses)
            assertEquals(1, plays)
        } finally {
            onMain { follower.stop() }
            session.release()
        }
    }
}
