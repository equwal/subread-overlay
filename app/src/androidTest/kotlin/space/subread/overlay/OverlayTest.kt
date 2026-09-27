package space.subread.overlay

import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.subread.overlay.core.Cue
import space.subread.overlay.core.CueIndex
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    private var touches = 0
    private var scrolls = 0
    private val shared = CopyOnWriteArrayList<String>()
    private val events = object : OverlayView.Events {
        override fun onDrag(dx: Float, dy: Float) = Unit
        override fun onDragEnd() = Unit
        override fun onClose() = Unit
        override fun onTouchWord() { touches++ }
        override fun onLookUp(word: String) { lookedUp += word }
        override fun onShare(word: String) { shared += word }
        override fun onTogglePlay() = Unit
        override fun onShiftLines(steps: Int) = Unit
        override fun onNudge(ms: Long) = Unit
        override fun onTouchScrolled() { scrolls++ }
    }

    /** The x of the middle of the character at [offset] of [row], in the view of the row. */
    private fun OverlayView.middleOf(row: Int, offset: Int): Float {
        val view = rowView(row)!!
        val layout = view.layout
        return view.totalPaddingLeft + (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2
    }

    /** A y just over the baseline of the character at [offset] of [row], in the view of the row. */
    private fun OverlayView.rowOf(row: Int, offset: Int): Float {
        val view = rowView(row)!!
        return view.totalPaddingTop + view.layout.getLineBaseline(view.layout.getLineForOffset(offset)) - 5f
    }

    /** Gives a touch event at [x], [y] of the view of [row] to that view. */
    private fun OverlayView.touch(row: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = android.view.MotionEvent.obtain(now, now, action, x, y, 0)
        rowView(row)!!.dispatchTouchEvent(event)
        event.recycle()
    }

    /** A panel with [lines] up to the line of now [now], in the window of an activity. */
    private fun panel(scenario: ActivityScenario<MainActivity>, lines: List<String>, now: Int, around: Boolean = false): OverlayView {
        lateinit var panel: OverlayView
        scenario.onActivity {
            panel = OverlayView(it, events)
            panel.setTextSize(30f)
            it.addContentView(panel, ViewGroup.LayoutParams(-1, -2))
            panel.showLines(lines, now, around)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return panel
    }

    @Test
    fun aTouchPausesAtOnceAndTheLiftLooksUpTheSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val panel = panel(scenario, listOf("reading along is easy"), 0)
            scenario.onActivity {
                val line = "reading along is easy"
                val start = line.indexOf("along")
                val last = line.lastIndex
                panel.touch(0, android.view.MotionEvent.ACTION_DOWN, panel.middleOf(0, start + 2), panel.rowOf(0, start + 2))
                assertEquals("the finger on a word pauses the player", 1, touches)
                assertEquals("no lookup before the finger lifts", emptyList<String>(), lookedUp)
                panel.touch(0, android.view.MotionEvent.ACTION_MOVE, panel.middleOf(0, last), panel.rowOf(0, last))
                panel.touch(0, android.view.MotionEvent.ACTION_UP, panel.middleOf(0, last), panel.rowOf(0, last))
                assertEquals(listOf("along is easy"), lookedUp)

                val found = arrayListOf<android.view.View>()
                panel.findViewsWithText(found, "Look up", android.view.View.FIND_VIEWS_WITH_TEXT)
                assertEquals("no \"Look up\" button", 0, found.size)

                // A tap beside the words neither pauses nor looks up.
                val right = panel.rowView(0)!!.width - 1f
                panel.touch(0, android.view.MotionEvent.ACTION_DOWN, right, panel.rowOf(0, last))
                panel.touch(0, android.view.MotionEvent.ACTION_UP, right, panel.rowOf(0, last))
                assertNull(panel.selection)
                assertEquals(1, touches)
                assertEquals(listOf("along is easy"), lookedUp)
            }
        }
    }

    @Test
    fun aTouchThatBecomesAScrollSelectsNothingAndLooksNothingUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val panel = panel(scenario, listOf("reading along is easy"), 0)
            scenario.onActivity {
                val start = "reading along is easy".indexOf("along")
                panel.touch(0, android.view.MotionEvent.ACTION_DOWN, panel.middleOf(0, start), panel.rowOf(0, start))
                assertEquals(1, touches)
                // The list takes the finger for a scroll: the row gets a cancel.
                panel.touch(0, android.view.MotionEvent.ACTION_CANCEL, panel.middleOf(0, start), panel.rowOf(0, start))
                assertNull(panel.selection)
                assertEquals("the player can play again", 1, scrolls)
                assertEquals(emptyList<String>(), lookedUp)
            }
        }
    }

    @Test
    fun aTapSelectsTheWordUnderTheFingerAndADragSelectsMore() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val lines = listOf("reading along is easy", "the next line")
            val panel = panel(scenario, lines, 0)
            scenario.onActivity {
                val start = lines[0].indexOf("along")
                // The middle of the third letter of "along".
                panel.selectAt(0, panel.middleOf(0, start + 2), panel.rowOf(0, start + 2))
                assertEquals(start..start + 4, panel.selection?.range)

                // A drag to the last word selects the three words.
                val last = lines[0].lastIndex
                panel.selectAt(0, panel.middleOf(0, last), panel.rowOf(0, last), extend = true)
                assertEquals(start..last, panel.selection?.range)
                assertEquals(lines[0], panel.selection?.text)

                val found = arrayListOf<android.view.View>()
                panel.findViewsWithText(found, "Share", android.view.View.FIND_VIEWS_WITH_TEXT)
                found.single().performClick()
                assertEquals(listOf("along is easy"), shared)

                // The dictionary closed.
                panel.clearSelection()
                assertNull(panel.selection)

                // The next line comes: the selection in the line before stays.
                panel.selectAt(0, panel.middleOf(0, start + 2), panel.rowOf(0, start + 2))
                panel.showLines(lines, 1, around = false)
                assertEquals(0, panel.selection?.row)
                assertEquals("along", panel.selection?.words)
            }
        }
    }

    @Test
    fun theLinesAroundAreOnTheirOwnRowsAndAWordOfThemCanBeSelected() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val lines = listOf("the line before", "the line of now", "the line after")
            val panel = panel(scenario, lines, 1, around = true)
            scenario.onActivity {
                assertEquals("the three lines are rows on the screen", 3, panel.list.childCount)
                val start = lines[2].indexOf("after")
                panel.selectAt(2, panel.middleOf(2, start + 1), panel.rowOf(2, start + 1))
                assertEquals(2, panel.selection?.row)
                assertEquals("after", panel.selection?.words)

                // The same lines again change nothing; other lines take the selection away.
                panel.showLines(lines, 1, around = true)
                assertEquals("after", panel.selection?.words)
                panel.showLines(listOf("the line of now"), 0, around = false)
                assertNull(panel.selection)
            }
        }
    }

    @Test
    fun theLinesUseTheFullWidthAndThePlayButtonIsAnIcon() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val panel = panel(scenario, listOf("reading along is easy"), 0)
            scenario.onActivity {
                assertEquals("no button beside the lines", panel.width - panel.paddingLeft - panel.paddingRight, panel.list.width)
                assertEquals(panel.list.width, panel.rowView(0)!!.width)
                panel.showPlaying(true)
                val found = arrayListOf<android.view.View>()
                panel.findViewsWithText(found, "Pause the player", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                assertTrue("the pause button is a black icon, not an emoji", found.single() is android.widget.ImageButton)
                found.clear()
                panel.findViewsWithText(found, "⏸", android.view.View.FIND_VIEWS_WITH_TEXT)
                assertEquals(0, found.size)
            }
        }
    }

    @Test
    fun aScrollBackStopsTheFollowAndNowFollowsAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val lines = List(41) { "line number $it of the film" }
            val panel = panel(scenario, lines, 39)
            scenario.onActivity {
                assertTrue(panel.following)
                assertEquals("the line of now is the last row", 39, panel.list.lastVisiblePosition)
            }
            // A slow drag down on the lines, as a finger does it.
            val list = panel.list
            val x = list.width / 2f
            val start = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float, time: Long) {
                val event = android.view.MotionEvent.obtain(start, start + time, action, x, y, 0)
                scenario.onActivity { list.dispatchTouchEvent(event) }
                event.recycle()
            }
            send(android.view.MotionEvent.ACTION_DOWN, 5f, 0)
            for (step in 1..20) send(android.view.MotionEvent.ACTION_MOVE, 5f + step * 20f, step * 50L)
            send(android.view.MotionEvent.ACTION_UP, 405f, 2_000)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            var first = -1
            scenario.onActivity {
                assertFalse("the list reads back", panel.following)
                assertTrue("an older line is on the screen", panel.list.firstVisiblePosition < 39)
                // The next line does not move the list while the user reads back.
                first = panel.list.firstVisiblePosition
                panel.showLines(lines, 40, around = false)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertEquals(first, panel.list.firstVisiblePosition)
                val found = arrayListOf<android.view.View>()
                panel.findViewsWithText(found, "Back to the line of now", android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                found.single().performClick()
                assertTrue(panel.following)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertEquals(40, panel.list.lastVisiblePosition) }
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

/** The activity that shows nothing and waits for the dictionary to close. */
@RunWith(AndroidJUnit4::class)
class LookupActivityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Starts a lookup of [target]. Returns what the end of the lookup said, or null when no end came. */
    private fun lookUp(target: Intent): Boolean? {
        val ends = LinkedBlockingQueue<Boolean>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LookupActivity.start(context, target) { started -> ends += started }
        }
        return ends.poll(10, TimeUnit.SECONDS)
    }

    @Test
    fun theLookupEndsWhenTheDictionaryCloses() {
        // A LookupActivity without a target closes at once: it plays the part of a dictionary that the user closes.
        assertEquals(true, lookUp(Intent(context, LookupActivity::class.java)))
    }

    @Test
    fun aDictionaryThatDoesNotStartEndsTheLookup() {
        assertEquals(false, lookUp(Intent("space.subread.overlay.test.NO_SUCH_DICTIONARY")))
    }

    /** The empty window of the lookup lets each touch through to the player, also while SubRead Anki records. */
    @Test
    fun theWindowOfTheLookupTakesNoTouch() {
        val ends = LinkedBlockingQueue<Boolean>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The settings screen of this app plays the part of the dictionary: it stays open over the lookup.
        instrumentation.runOnMainSync { LookupActivity.start(context, Intent(context, MainActivity::class.java)) { ends += it } }
        fun find(kind: Class<*>): android.app.Activity? {
            var found: android.app.Activity? = null
            val until = SystemClock.elapsedRealtime() + 5_000
            while (found == null && SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync {
                    found = Stage.entries.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.firstOrNull { kind.isInstance(it) }
                }
                if (found == null) Thread.sleep(50)
            }
            return found
        }
        val lookup = find(LookupActivity::class.java)!!
        assertTrue(lookup.window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        instrumentation.runOnMainSync { find(MainActivity::class.java)?.finish() }
        assertEquals("the dictionary closed", true, ends.poll(10, TimeUnit.SECONDS))
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
