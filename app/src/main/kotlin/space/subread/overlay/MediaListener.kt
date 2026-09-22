package space.subread.overlay

import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import space.subread.overlay.core.CueIndex
import space.subread.overlay.core.Srt
import kotlin.concurrent.thread

/**
 * Holds the panel, and follows the media sessions of the device.
 *
 * Android gives the media sessions of other apps only to a notification listener. This service
 * reads no notification: it has no `onNotificationPosted`. The system keeps a listener running,
 * so the panel needs no foreground service and no notification of its own.
 */
class MediaListener : NotificationListenerService(), OverlayView.Events {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var follower: Follower
    private var panel: OverlayView? = null
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
        removePanel()
    }

    /** Puts the panel on the screen. False when the user did not allow the panel yet. */
    fun showPanel(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        store.shown = true
        if (panel == null) {
            params.x = store.x
            params.y = store.y
            panel = OverlayView(this, this).also { getSystemService(WindowManager::class.java).addView(it, params) }
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
            follower.index = CueIndex(emptyList())
            return
        }
        thread(name = "subtitles") {
            val cues = runCatching {
                contentResolver.openInputStream(file)!!.use { Srt.parse(it.readBytes().decodeToString()) }
            }.getOrDefault(emptyList())
            handler.post {
                follower.index = CueIndex(cues)
                follower.follow(sessions.getActiveSessions(me))
            }
        }
    }

    private fun removePanel() {
        panel?.let { getSystemService(WindowManager::class.java).removeView(it) }
        panel = null
    }

    private fun show(at: Int, hasPlayer: Boolean, playing: Boolean) {
        val view = panel ?: return
        view.showPlaying(playing)
        val cues = follower.index.cues
        val line = cues.getOrNull(at)?.text
        when {
            store.subtitles == null -> view.showStatus(getString(R.string.status_no_file))
            cues.isEmpty() -> view.showStatus(getString(R.string.status_empty_file))
            !hasPlayer -> view.showStatus(getString(R.string.status_no_player))
            line == null -> view.showStatus(getString(R.string.status_before_first_line))
            store.linesAround -> view.showLine(line, cues.getOrNull(at - 1)?.text, cues.getOrNull(at + 1)?.text)
            else -> view.showLine(line)
        }
    }

    override fun onDrag(dx: Float, dy: Float) {
        params.x += dx.toInt()
        params.y += dy.toInt()
        panel?.let { getSystemService(WindowManager::class.java).updateViewLayout(it, params) }
    }

    override fun onDragEnd() {
        store.x = params.x
        store.y = params.y
    }

    override fun onClose() = hidePanel()

    /** The player pauses for the lookup. The play button of the panel starts it again. */
    override fun onLookUp(word: String) {
        follower.pause()
        runCatching { startActivity(Lookup.intent(word, store.dictionary)) }.onFailure {
            Toast.makeText(this, R.string.no_dictionary, Toast.LENGTH_LONG).show()
        }
    }

    override fun onShare(word: String) {
        follower.pause()
        runCatching { startActivity(Lookup.share(word)) }
    }

    override fun onTogglePlay() {
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
