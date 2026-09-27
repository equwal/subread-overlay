package space.subread.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.IntentCompat

/**
 * Opens the dictionary or SubRead Anki for a word, and tells the panel when it closes.
 *
 * The panel is not an activity, so it cannot get a result from the dictionary. This activity
 * shows nothing. It starts the dictionary for a result. The result comes back when the
 * dictionary closes: a tap beside it, its close button, or the back key. Then the panel removes
 * the selection and the player plays again. The chooser of Android gives the result of the app
 * that the user picks to this activity, so a look-up without a chosen dictionary works too.
 */
class LookupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window of this activity covers the screen, but it shows nothing. A touch must go
        // through it to the player below: SubRead Anki lets the user use the player while it records.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // Android made this activity again, after a rotation or a stop of the process. The
        // dictionary is still open over it: the activity waits for its result.
        if (savedInstanceState == null) take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent) {
        val token = intent.getIntExtra(EXTRA_TOKEN, 0)
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java) ?: return finish()
        runCatching { startActivityForResult(target, token) }.onFailure { end(token, started = false) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        end(requestCode, started = true)
    }

    /**
     * The look-up with [token] ended. A second look-up while the dictionary is open closes the
     * first dictionary. The result of that first dictionary can come after the second look-up
     * started: its token is old, and the second look-up goes on. Without a panel that waits,
     * after a stop of the process, the activity always closes.
     */
    private fun end(token: Int, started: Boolean) {
        val callback = onEnd
        if (token != current && callback != null) return
        onEnd = null
        finish()
        callback?.invoke(started)
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_TOKEN = "token"

        /** The token of the newest look-up. It is the request code, so it stays in 16 bits. */
        private var current = 0
        private var onEnd: ((Boolean) -> Unit)? = null

        /**
         * Starts [target] for a result: a dictionary, the chooser, or SubRead Anki. Calls
         * [onEnd] on the main thread when it closes, with false when it did not start. From
         * the main thread.
         */
        fun start(context: Context, target: Intent, onEnd: (started: Boolean) -> Unit) {
            current = (current + 1) and 0xFFFF
            this.onEnd = onEnd
            val intent = Intent(context, LookupActivity::class.java)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_TOKEN, current)
                // The panel is not an activity. The flags use the one activity of the look-up
                // task again, and close a dictionary that is still open over it.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { context.startActivity(intent) }.onFailure {
                this.onEnd = null
                onEnd(false)
            }
        }
    }
}
