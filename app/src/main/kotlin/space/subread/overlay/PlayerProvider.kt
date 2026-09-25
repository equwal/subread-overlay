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
import android.provider.DocumentsContract
import space.subread.overlay.core.CueIndex
import space.subread.overlay.core.PlayClock
import space.subread.overlay.core.Srt

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
 * `query` of the path `line` returns the subtitle line of now, for a flash card app: the row
 * has `state` and the raw report of the player (`reported_position`, `reported_at` on the
 * clock of `SystemClock.elapsedRealtime()`, `speed`, `playing`), the `offset` the user set, and
 * the line: `index` (-1 before the first line), `start`, `end`, `text`, `before`, `after`. The
 * times of the line are on the clock of the subtitle file: `offset` later than the player. The
 * query parameter `position` (milliseconds, on the clock of the player) picks the line for
 * that position in place of the position of now.
 *
 * `call` takes the method `play`, `pause` or `seek` (the argument is the position in
 * milliseconds), and returns the state line in the bundle key `state`.
 *
 * A caption app, one that makes captions from live audio, sends its lines with the method `line`
 * (the argument is the text, the extra `partial` is true while the sentence goes on) and `end`
 * when it stops. The panel shows the newest line, in place of the subtitle file, until `end`.
 * The bundle key `live` answers `ok`, or the reason the panel cannot show the line:
 * `no_notification_access`, `no_overlay_permission` or `panel_hidden`.
 *
 * The provider is open to each app. Each app can already send the media keys to the player, so
 * this gives no new control over the device. A line is shown, not kept or sent.
 */
class PlayerProvider : ContentProvider() {

    private val sessions by lazy { context!!.getSystemService(MediaSessionManager::class.java) }
    private val listener by lazy { ComponentName(context!!, MediaListener::class.java) }

    /** The subtitle file, read once for the `line` rows and again when the user chooses another. */
    private var cachedFile: String? = null
    private var cachedModified: Long? = null
    private var cachedIndex: CueIndex? = null
    private val cacheLock = Any()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        if (uri.lastPathSegment == PATH_LINE) return lineRow(uri.getQueryParameter(PARAM_POSITION)?.toLongOrNull())
        return MatrixCursor(arrayOf(COLUMN_STATE)).apply { addRow(arrayOf(stateLine())) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == METHOD_LINE || method == METHOD_END) {
            val listener = MediaListener.instance
            val answer = when {
                listener == null -> ERROR_NO_ACCESS
                method == METHOD_END -> listener.endLive()
                else -> listener.liveLine(arg.orEmpty(), extras?.getBoolean(EXTRA_PARTIAL) == true)
            }
            return Bundle().apply { putString(KEY_LIVE, answer) }
        }
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

    /** The `line` row: the report of the player as it came, and the line for its position of now, or for [positionOverride]. */
    private fun lineRow(positionOverride: Long?): Cursor {
        val cursor = MatrixCursor(LINE_COLUMNS)
        val state = runCatching { player()?.playbackState }.getOrNull()
        val nowMs = SystemClock.elapsedRealtime()
        val reportedPosition = state?.position?.takeIf { it >= 0 } ?: -1L
        val reportedAt = if (state == null) -1L else state.lastPositionUpdateTime.takeIf { it > 0 } ?: nowMs
        val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val store = Store(context!!)
        val offset = store.offsetMs
        val position = positionOverride
            ?: if (reportedPosition >= 0) PlayClock(reportedPosition, reportedAt, speed, playing).positionAt(nowMs) else null
        val index = subtitles(store)
        val at = if (position != null && index != null) index.indexAt(position + offset) else -1
        val cues = index?.cues.orEmpty()
        val cue = cues.getOrNull(at)
        cursor.addRow(
            arrayOf<Any?>(
                stateLine(), reportedPosition, reportedAt, speed, if (playing) 1 else 0, offset,
                at, cue?.startMs, cue?.endMs, cue?.text, cues.getOrNull(at - 1)?.text, cues.getOrNull(at + 1)?.text,
            ),
        )
        return cursor
    }

    /** The cues of the chosen subtitle file, or null without one. Read again when the file or its date changed. */
    private fun subtitles(store: Store): CueIndex? {
        val file = store.subtitles ?: return null
        val resolver = context!!.contentResolver
        val modified = runCatching {
            resolver.query(file, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        }.getOrNull()
        synchronized(cacheLock) {
            if (file.toString() == cachedFile && modified == cachedModified) return cachedIndex
            val cues = runCatching {
                resolver.openInputStream(file)!!.use { Srt.parse(it.readBytes().decodeToString()) }
            }.getOrDefault(emptyList())
            cachedFile = file.toString()
            cachedModified = modified
            cachedIndex = CueIndex(cues)
            return cachedIndex
        }
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment == PATH_LINE) "vnd.android.cursor.item/vnd.space.subread.overlay.line"
        else "vnd.android.cursor.item/vnd.space.subread.overlay.state"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        // The debug build has its own authority, so it installs next to the release.
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".player"
        const val PATH_LINE = "line"
        const val PARAM_POSITION = "position"
        const val COLUMN_STATE = "state"
        const val COLUMN_REPORTED_POSITION = "reported_position"
        const val COLUMN_REPORTED_AT = "reported_at"
        const val COLUMN_SPEED = "speed"
        const val COLUMN_PLAYING = "playing"
        const val COLUMN_OFFSET = "offset"
        const val COLUMN_INDEX = "index"
        const val COLUMN_START = "start"
        const val COLUMN_END = "end"
        const val COLUMN_TEXT = "text"
        const val COLUMN_BEFORE = "before"
        const val COLUMN_AFTER = "after"
        val LINE_COLUMNS = arrayOf(
            COLUMN_STATE, COLUMN_REPORTED_POSITION, COLUMN_REPORTED_AT, COLUMN_SPEED, COLUMN_PLAYING, COLUMN_OFFSET,
            COLUMN_INDEX, COLUMN_START, COLUMN_END, COLUMN_TEXT, COLUMN_BEFORE, COLUMN_AFTER,
        )
        const val METHOD_PLAY = "play"
        const val METHOD_PAUSE = "pause"
        const val METHOD_SEEK = "seek"
        const val METHOD_LINE = "line"
        const val METHOD_END = "end"
        const val EXTRA_PARTIAL = "partial"
        const val KEY_LIVE = "live"
        const val LIVE_OK = "ok"
        const val ERROR_NO_ACCESS = "no_notification_access"
        const val ERROR_NO_PLAYER = "no_player"
        const val ERROR_NO_OVERLAY = "no_overlay_permission"
        const val ERROR_PANEL_HIDDEN = "panel_hidden"

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
