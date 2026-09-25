package space.subread.overlay

import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The provider that a reader app asks for the position. The test app plays the part of the
 * player with its own media session. The device must give this app notification access, else
 * the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class PlayerProviderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = "content://${PlayerProvider.AUTHORITY}/state".toUri()
    private val lineUri = "content://${PlayerProvider.AUTHORITY}/${PlayerProvider.PATH_LINE}".toUri()

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

    /** A flash card app asks for the line of a position. No player and no notification access are needed for that. */
    @Test
    fun aFlashCardAppReadsTheLineOfAPosition() {
        val store = Store(context)
        val before = store.subtitles
        val beforeOffset = store.offsetMs
        val file = File(context.cacheDir, "line-test.srt").apply {
            writeText("1\n00:01:30,000 --> 00:01:33,000\nFirst line.\n\n2\n00:01:36,000 --> 00:01:39,500\nSecond line.\n\n")
        }
        try {
            store.subtitles = Uri.fromFile(file)
            store.offsetMs = 1_000
            // The player is at 95 s; the file is shifted 1 s later: the line at 96 s of the file.
            val at95 = lineUri.buildUpon().appendQueryParameter(PlayerProvider.PARAM_POSITION, "95000").build()
            context.contentResolver.query(at95, null, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Second line.", it.getString(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_TEXT)))
                assertEquals(96_000L, it.getLong(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_START)))
                assertEquals(99_500L, it.getLong(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_END)))
                assertEquals(1L, it.getLong(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_INDEX)))
                assertEquals(1_000L, it.getLong(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_OFFSET)))
                assertEquals("First line.", it.getString(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_BEFORE)))
                assertTrue(it.isNull(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_AFTER)))
            }
            // Before the first line: no text.
            val at10 = lineUri.buildUpon().appendQueryParameter(PlayerProvider.PARAM_POSITION, "10000").build()
            context.contentResolver.query(at10, null, null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(-1L, it.getLong(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_INDEX)))
                assertTrue(it.isNull(it.getColumnIndexOrThrow(PlayerProvider.COLUMN_TEXT)))
            }
        } finally {
            store.subtitles = before
            store.offsetMs = beforeOffset
            file.delete()
        }
    }
}
