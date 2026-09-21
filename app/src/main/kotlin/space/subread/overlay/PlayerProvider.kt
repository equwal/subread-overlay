package space.subread.overlay

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import space.subread.overlay.core.PlayClock

/**
 * Gives the position of the media player to other apps, and takes play, pause and seek from them.
 *
 * A reader app, for example KOReader with the SubRead plugin, has no notification access, so it
 * cannot see the media session of the player. This app has that access, so it answers for them.
 *
 * `query` returns one row with one column, `state`. Its value is one line:
 *
 *     playing=1;position=96153;speed=1.0;package=de.ph1b.audiobook
 *
 * The position is in milliseconds, computed for the moment of the query. A problem is one line
 * `error=<reason>`: `no_notification_access` or `no_player`.
 *
 * `call` takes the method `play`, `pause` or `seek` (the argument is the position in
 * milliseconds), and returns the state line in the bundle key `state`.
 *
 * The provider is open to each app. Each app can already send the media keys to the player, so
 * this gives no new control over the device.
 */
class PlayerProvider : ContentProvider() {

    private val sessions by lazy { context!!.getSystemService(MediaSessionManager::class.java) }
    private val listener by lazy { ComponentName(context!!, MediaListener::class.java) }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(COLUMN_STATE)).apply { addRow(arrayOf(stateLine())) }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val player = runCatching { player() }.getOrNull()
        val controls = player?.transportControls
        when (method) {
            METHOD_PLAY -> controls?.play()
            METHOD_PAUSE -> controls?.pause()
            METHOD_SEEK -> arg?.toLongOrNull()?.let { controls?.seekTo(it) }
        }
        return Bundle().apply { putString(COLUMN_STATE, stateLine()) }
    }

    /** The session that plays, else the first active one. Null when there is none. */
    private fun player(): MediaController? {
        val active = sessions.getActiveSessions(listener)
        return active.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: active.firstOrNull()
    }

    private fun stateLine(): String {
        val player = try {
            player()
        } catch (e: SecurityException) {
            return "error=$ERROR_NO_ACCESS"
        } ?: return "error=$ERROR_NO_PLAYER"
        val state = player.playbackState ?: return "error=$ERROR_NO_PLAYER"
        return stateLine(state, player.packageName, SystemClock.elapsedRealtime())
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.space.subread.overlay.state"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        // The debug build has its own authority, so it installs next to the release.
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".player"
        const val COLUMN_STATE = "state"
        const val METHOD_PLAY = "play"
        const val METHOD_PAUSE = "pause"
        const val METHOD_SEEK = "seek"
        const val ERROR_NO_ACCESS = "no_notification_access"
        const val ERROR_NO_PLAYER = "no_player"

        /** The state line for a report of the player, at [nowMs] on the clock of the device. */
        fun stateLine(state: PlaybackState, packageName: String, nowMs: Long): String {
            val playing = state.state == PlaybackState.STATE_PLAYING
            val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
            // A report without a position: the same as the panel, the position is unknown.
            val position = if (state.position >= 0) {
                val reportedAt = state.lastPositionUpdateTime.takeIf { it > 0 } ?: nowMs
                PlayClock(state.position, reportedAt, speed, playing).positionAt(nowMs)
            } else {
                -1
            }
            return "playing=${if (playing) 1 else 0};position=$position;speed=$speed;package=$packageName"
        }
    }
}
