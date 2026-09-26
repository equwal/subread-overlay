package space.subread.overlay.core

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookUpPauseTest {

    /** 0: a touch on a paused player, 1: a touch that pauses, 2: open, 3: scroll, 4: close, 5: the play button. */
    private val steps = Arb.list(Arb.int(0..5), 0..60)

    /**
     * The player plays again once for each touch that paused it: when the dictionary closes, or
     * when the touch becomes a scroll while no dictionary is open. Never after the play button.
     */
    @Test
    fun thePlayerPlaysAgainOnlyWhenATouchPausedIt(): Unit = runBlocking {
        checkAll(steps) { list ->
            val pause = LookUpPause()
            var touchPaused = false
            var open = false
            for (step in list) {
                when (step) {
                    0 -> pause.touched(false)
                    1 -> {
                        pause.touched(true)
                        touchPaused = true
                    }
                    2 -> {
                        pause.opened()
                        open = true
                    }
                    3 -> {
                        val play = !open && touchPaused
                        assertEquals("scroll after $list", play, pause.scrolled())
                        if (play) touchPaused = false
                    }
                    4 -> {
                        assertEquals("close after $list", touchPaused, pause.closed())
                        touchPaused = false
                        open = false
                    }
                    5 -> {
                        pause.toggled()
                        touchPaused = false
                    }
                }
            }
        }
    }

    @Test
    fun theDictionaryClosesAndThePlayerPlaysAgain() {
        val pause = LookUpPause()
        pause.touched(true)
        pause.opened()
        assertTrue(pause.closed())
        assertFalse("once only", pause.closed())
    }

    @Test
    fun aPlayerThatTheUserPausedStaysPaused() {
        val pause = LookUpPause()
        pause.touched(false)
        pause.opened()
        assertFalse(pause.closed())

        pause.touched(true)
        pause.opened()
        pause.toggled()
        assertFalse("the play button came after the touch", pause.closed())
    }

    @Test
    fun aScrollWhileTheDictionaryIsOpenWaitsForTheDictionary() {
        val pause = LookUpPause()
        pause.touched(true)
        pause.opened()
        pause.touched(false)
        assertFalse(pause.scrolled())
        assertTrue(pause.closed())

        pause.touched(true)
        assertTrue("no dictionary is open", pause.scrolled())
    }
}
