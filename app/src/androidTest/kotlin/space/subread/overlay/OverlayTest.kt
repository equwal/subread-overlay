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
    private val events = object : OverlayView.Events {
        override fun onDrag(dx: Float, dy: Float) = Unit
        override fun onDragEnd() = Unit
        override fun onClose() = Unit
        override fun onLookUp(word: String) { lookedUp += word }
        override fun onShiftLines(steps: Int) = Unit
        override fun onNudge(ms: Long) = Unit
    }

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
                val layout = panel.text.layout
                val start = panel.line.indexOf("along")
                // The middle of the third letter of "along".
                val x = panel.text.totalPaddingLeft + (layout.getPrimaryHorizontal(start + 2) + layout.getPrimaryHorizontal(start + 3)) / 2
                val y = panel.text.totalPaddingTop + layout.getLineBaseline(0) - 5f
                panel.selectAt(x, y)
                assertEquals(start..start + 4, panel.selection)

                // A drag to the last word selects the three words.
                val end = panel.text.totalPaddingLeft + layout.getPrimaryHorizontal(panel.line.length - 1) - 2f
                panel.selectAt(end, y, extend = true)
                assertEquals(start..panel.line.lastIndex, panel.selection)

                val found = arrayListOf<android.view.View>()
                panel.findViewsWithText(found, "Look up", android.view.View.FIND_VIEWS_WITH_TEXT)
                found.single().performClick()
                assertEquals(listOf("along is easy"), lookedUp)

                // The next line has no selection from the line before.
                panel.showLine("the next line")
                assertNull(panel.selection)
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
                follower = Follower(main) { cue, _ -> lines += cue?.text }
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
                follower = Follower(main) { cue, _ -> lines += cue?.text }
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
            val follower = Follower(main) { _, hasPlayer -> players += hasPlayer }
            follower.follow(emptyList())
        }
        assertEquals(false, players.last())
    }
}
