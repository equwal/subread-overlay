package space.subread.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import space.subread.overlay.core.CueIndex
import space.subread.overlay.core.LiveLines
import space.subread.overlay.core.LookUpPause
import space.subread.overlay.core.Srt
import space.subread.overlay.core.Transcript
import kotlin.concurrent.thread

/**
 * Holds the panel, and follows the media sessions of the device.
 *
 * Android gives the media sessions of other apps only to a notification listener. This service
 * reads no notification: it has no `onNotificationPosted`. The system keeps a listener running,
 * so the panel needs no foreground service and no notification of its own.
 *
 * A caption app can send lines through [PlayerProvider]. While it does, the panel shows its
 * lines and not the subtitle file.
 */
class MediaListener : NotificationListenerService(), OverlayView.Events {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var follower: Follower
    private var panel: OverlayView? = null
    private val live = LiveLines()

    /** The text of each line of the subtitle file, for the panel. One list for each file. */
    private var fileLines: List<String> = emptyList()

    /** True while a caption app sends lines. The follower then does not draw on the panel. */
    private var liveOn = false

    /** True when a live line came while a word was selected: the panel shows it after the selection. */
    private var livePending = false

    /** Decides when the player plays again after a touch on a word paused it. */
    private val lookUpPause = LookUpPause()

    /** Where the top of the panel was before it grew for a read back; null while it follows the line of now. */
    private var browseY: Int? = null

    /** The selection on the panel, for the `line` query of [PlayerProvider]. Other threads read it. */
    @Volatile
    var selection: OverlayView.Selection? = null
        private set

    private val liveTimeout = Runnable { endLive() }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // The player below keeps the keys and each touch that is not on the panel.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val sessions by lazy { getSystemService(MediaSessionManager::class.java) }
    private val me by lazy { ComponentName(this, MediaListener::class.java) }
    private val onSessions = MediaSessionManager.OnActiveSessionsChangedListener {
        // A hidden panel follows nothing: no work while the user does not read along.
        if (panel != null) follower.follow(it.orEmpty())
    }

    override fun onListenerConnected() {
        store = Store(this)
        follower = Follower(handler) { at, hasPlayer, playing -> show(at, hasPlayer, playing) }
        sessions.addOnActiveSessionsChangedListener(onSessions, me, handler)
        instance = this
        if (store.shown) showPanel()
    }

    override fun onListenerDisconnected() {
        instance = null
        sessions.removeOnActiveSessionsChangedListener(onSessions)
        follower.stop()
        handler.removeCallbacks(liveTimeout)
        removePanel()
    }

    /** Puts the panel on the screen. False when the user did not allow the panel yet. */
    fun showPanel(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        store.shown = true
        if (panel == null) {
            params.x = store.x
            params.y = store.y
            browseY = null
            panel = OverlayView(this, this, Anki.installed(this)).also { getSystemService(WindowManager::class.java).addView(it, params) }
        }
        reload()
        return true
    }

    fun hidePanel() {
        store.shown = false
        follower.stop()
        removePanel()
    }

    /** Reads the settings and the subtitle file again. */
    fun reload() {
        val view = panel ?: return
        view.setTextSize(store.textSizeSp)
        view.setTransparency(store.transparencyPercent)
        view.showOffset(store.offsetMs)
        follower.offsetMs = store.offsetMs
        val file = store.subtitles
        if (file == null) {
            fileLines = emptyList()
            follower.index = CueIndex(emptyList())
            return
        }
        thread(name = "subtitles") {
            val cues = runCatching {
                contentResolver.openInputStream(file)!!.use { Srt.parse(it.readBytes().decodeToString()) }
            }.getOrDefault(emptyList())
            handler.post {
                fileLines = cues.map { it.text }
                follower.index = CueIndex(cues)
                follower.follow(sessions.getActiveSessions(me))
            }
        }
    }

    private fun removePanel() {
        panel?.let { getSystemService(WindowManager::class.java).removeView(it) }
        panel = null
        selection = null
        // The panel comes back at the place that the user chose, not at the place of a read back.
        browseY?.let { params.y = it }
        browseY = null
    }

    /**
     * A line from a caption app, from any thread. Returns [PlayerProvider.LIVE_OK] when the panel
     * shows it, else the reason: the panel needs the overlay permission, and the user must have
     * it on the screen. A panel that the user closed does not come back for a line.
     */
    fun liveLine(text: String, partial: Boolean): String {
        if (!Settings.canDrawOverlays(this)) return PlayerProvider.ERROR_NO_OVERLAY
        if (!store.shown) return PlayerProvider.ERROR_PANEL_HIDDEN
        handler.post {
            if (!liveOn) {
                liveOn = true
                live.clear()
            }
            live.line(text, partial)
            // A caption app that dies without `end` must not leave its last line for ever.
            handler.removeCallbacks(liveTimeout)
            handler.postDelayed(liveTimeout, LIVE_TIMEOUT_MS)
            if (panel == null) showPanel() else showLive()
        }
        return PlayerProvider.LIVE_OK
    }

    /** The caption app stopped: the panel goes back to the subtitle file. From any thread. */
    fun endLive(): String {
        handler.post {
            if (!liveOn) return@post
            liveOn = false
            livePending = false
            live.clear()
            handler.removeCallbacks(liveTimeout)
            if (panel != null) {
                // The rows of the live lines go away: a selection in them too.
                panel?.clearSelection()
                reload()
            }
        }
        return PlayerProvider.LIVE_OK
    }

    /**
     * Shows the live lines: the final lines, then the newest line. While a word is selected, the
     * newest line waits: a line that changes under the finger would take the selection away
     * before the lookup.
     */
    private fun showLive() {
        val view = panel ?: return
        if (view.selection != null) {
            livePending = true
            return
        }
        livePending = false
        val rows = live.rows()
        view.showLines(rows, rows.lastIndex, store.linesAround)
    }

    private fun show(at: Int, hasPlayer: Boolean, playing: Boolean) {
        val view = panel ?: return
        if (liveOn) {
            showLive()
            return
        }
        view.showPlaying(playing)
        val cues = follower.index.cues
        when {
            store.subtitles == null -> view.showStatus(getString(R.string.status_no_file))
            cues.isEmpty() -> view.showStatus(getString(R.string.status_empty_file))
            !hasPlayer -> view.showStatus(getString(R.string.status_no_player))
            at < 0 -> view.showStatus(getString(R.string.status_before_first_line))
            else -> view.showLines(fileLines, at, store.linesAround)
        }
    }

    override fun onDrag(dx: Float, dy: Float) {
        params.x += dx.toInt()
        params.y += dy.toInt()
        browseY = browseY?.plus(dy.toInt())
        panel?.let { getSystemService(WindowManager::class.java).updateViewLayout(it, params) }
    }

    override fun onDragEnd() {
        store.x = params.x
        store.y = browseY ?: params.y
    }

    /** The panel grows for a read back, with its bottom edge where it is, or goes back to its place. */
    override fun onGrow(extra: Int): Int {
        val view = panel ?: return 0
        val manager = getSystemService(WindowManager::class.java)
        if (extra <= 0) {
            browseY?.let {
                params.y = it
                browseY = null
                manager.updateViewLayout(view, params)
            }
            return 0
        }
        val top = browseY ?: params.y.also { browseY = it }
        params.y = Transcript.grownTop(top, extra)
        manager.updateViewLayout(view, params)
        return top - params.y
    }

    override fun onClose() = hidePanel()

    override fun onSelectionCleared() {
        if (liveOn && livePending) showLive()
    }

    override fun onSelection(selection: OverlayView.Selection?) {
        this.selection = if (selection == null || !liveOn) selection else live(selection)
    }

    /**
     * A selection in a live line, for a card. The partial line of now has a mark at its end on
     * the panel: the card gets the line without it, and a selection stops before it.
     */
    private fun live(selection: OverlayView.Selection): OverlayView.Selection? {
        val isNow = selection.row == live.history.size
        val text = if (isNow && live.partial) LiveLines.plain(selection.text) else selection.text
        val last = selection.range.last.coerceAtMost(text.lastIndex)
        if (last < selection.range.first) return null
        return selection.copy(text = text, range = selection.range.first..last, live = true)
    }

    /** The player pauses for the lookup. It plays again when the dictionary closes. */
    override fun onTouchWord() {
        lookUpPause.touched(follower.pause())
    }

    /** The touch on a word became a scroll: the player plays again if the touch paused it. */
    override fun onTouchScrolled() {
        if (lookUpPause.scrolled()) follower.play()
    }

    override fun onLookUp(word: String) {
        lookUp(Lookup.intent(word, store.dictionary), R.string.no_dictionary)
    }

    /**
     * Opens [target] over the player. When it closes, the selection goes away, and the player
     * plays again if the touch paused it. When it does not start, a toast shows [failure].
     */
    private fun lookUp(target: Intent, failure: Int) {
        lookUpPause.opened()
        LookupActivity.start(this, target) { started ->
            if (!started) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
            panel?.clearSelection()
            if (lookUpPause.closed()) follower.play()
        }
    }

    override fun onShare(word: String) {
        follower.pause()
        runCatching { startActivity(Lookup.share(word)) }
    }

    /**
     * The word and its line go to SubRead Anki. The panel hides for a moment, so that the
     * screenshot of SubRead Anki shows the player and not the panel. When the pop-up of SubRead
     * Anki closes, the selection goes away, the same as after a lookup.
     */
    override fun onAnki(word: String) {
        lookUpPause.touched(follower.pause())
        val view = panel ?: return
        val source = follower.title ?: follower.player?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: ""
        // The line of the selection, without the mark of a partial live line.
        val intent = Anki.intent(word, selection?.text.orEmpty(), source)
        view.visibility = View.INVISIBLE
        handler.postDelayed({ lookUp(intent, R.string.no_anki) }, ANKI_HIDE_MS)
        handler.postDelayed({ panel?.visibility = View.VISIBLE }, ANKI_SHOW_MS)
    }

    override fun onTogglePlay() {
        // The user controls the player now: the end of the lookup does not start it.
        lookUpPause.toggled()
        if (!follower.pause()) follower.play()
    }

    override fun onShiftLines(steps: Int) {
        follower.shiftLines(steps)
        keepOffset()
    }

    override fun onNudge(ms: Long) {
        follower.offsetMs += ms
        keepOffset()
    }

    private fun keepOffset() {
        store.offsetMs = follower.offsetMs
        panel?.showOffset(follower.offsetMs)
    }

    companion object {
        /** Live lines end on their own this long after the last one. */
        const val LIVE_TIMEOUT_MS = 10 * 60_000L

        /** The panel hides this long before the card goes, and comes back this long after. */
        const val ANKI_HIDE_MS = 150L
        const val ANKI_SHOW_MS = 1800L

        /** The listener that the system runs now; null when the user did not allow it. */
        @Volatile
        var instance: MediaListener? = null
            private set

        fun isAllowed(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)
                ?.any { it.packageName == context.packageName } == true
    }
}
