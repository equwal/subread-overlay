package space.subread.overlay

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The provider that a reader app asks for the position. The test app plays the part of the
 * player with its own media session. The device must give this app notification access, else
 * the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class PlayerProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = "content://${PlayerProvider.AUTHORITY}/state".toUri()

    private fun state(state: Int, position: Long, speed: Float = 1f) = PlaybackState.Builder()
        .setState(state, position, speed, SystemClock.elapsedRealtime())
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO)
        .build()

    private fun read(): Map<String, String> {
        val cursor = context.contentResolver.query(uri, null, null, null, null)!!
        cursor.use {
            assertTrue(it.moveToFirst())
            val line = it.getString(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_STATE))
            return line.split(';').associate { part -> part.substringBefore('=') to part.substringAfter('=') }
        }
    }

    @Test
    fun theStateLineHasThePositionOfNow() {
        val paused = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 1_000, 1f, 5_000).build()
        assertEquals(
            "playing=0;position=1000;speed=1.0;package=p",
            PlayerProvider.stateLine(paused, "p", 9_000),
        )
        val playing = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 1_000, 2f, 5_000).build()
        assertEquals(
            "playing=1;position=9000;speed=2.0;package=p",
            PlayerProvider.stateLine(playing, "p", 9_000),
        )
        val unknown = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f, 0).build()
        assertEquals("playing=1;position=-1;speed=1.0;package=p", PlayerProvider.stateLine(unknown, "p", 9_000))
    }

    @Test
    fun aReaderAppReadsThePlayerAndControlsIt() {
        assumeTrue("notification access is not given to this app", MediaListener.isAllowed(context))
        val session = MediaSession(context, "test player")
        val seeks = mutableListOf<Long>()
        var plays = 0
        var pauses = 0
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { plays++ }
            override fun onPause() { pauses++ }
            override fun onSeekTo(pos: Long) { seeks += pos }
        })
        try {
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING, 96_000, speed = 1.5f))
            session.isActive = true

            val seen = read()
            assertEquals("1", seen["playing"])
            assertEquals("1.5", seen["speed"])
            assertEquals(context.packageName, seen["package"])
            val position = seen["position"]!!.toLong()
            assertTrue("position $position is not near 96000", position in 96_000..97_500)

            val resolver = context.contentResolver
            resolver.call(uri, PlayerProvider.METHOD_PAUSE, null, null)
            resolver.call(uri, PlayerProvider.METHOD_PLAY, null, null)
            val answer = resolver.call(uri, PlayerProvider.METHOD_SEEK, "12345", null)!!
            val until = SystemClock.elapsedRealtime() + 5_000
            while ((plays < 1 || pauses < 1 || seeks.isEmpty()) && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            assertEquals(1, plays)
            assertEquals(1, pauses)
            assertEquals(listOf(12_345L), seeks)
            assertTrue(answer.getString(PlayerProvider.COLUMN_STATE)!!.startsWith("playing="))
        } finally {
            session.release()
        }
    }
}
