package space.subread.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/** What the user chose. It stays when the app stops. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    var subtitles: Uri?
        get() = prefs.getString("subtitles", null)?.toUri()
        set(value) = prefs.edit {
            putString("subtitles", value?.toString())
            // The offset belongs to one file and its media. Another file starts at zero.
            putLong("offset", 0)
        }

    var subtitlesName: String?
        get() = prefs.getString("subtitles_name", null)
        set(value) = prefs.edit { putString("subtitles_name", value) }

    var offsetMs: Long
        get() = prefs.getLong("offset", 0)
        set(value) = prefs.edit { putLong("offset", value) }

    var textSizeSp: Float
        get() = prefs.getFloat("text_size", 22f)
        set(value) = prefs.edit { putFloat("text_size", value.coerceIn(12f, 60f)) }

    /** Where the panel is, in pixels from the top left of the screen. */
    var x: Int
        get() = prefs.getInt("x", 0)
        set(value) = prefs.edit { putInt("x", value) }

    var y: Int
        get() = prefs.getInt("y", 200)
        set(value) = prefs.edit { putInt("y", value) }

    /** True when the user wants the panel on the screen. The listener shows it again after a restart. */
    var shown: Boolean
        get() = prefs.getBoolean("shown", false)
        set(value) = prefs.edit { putBoolean("shown", value) }

    /** The app that gets the word. Null: Android asks each time. */
    var dictionary: ComponentName?
        get() = prefs.getString("dictionary", null)?.let(ComponentName::unflattenFromString)
        set(value) = prefs.edit { putString("dictionary", value?.flattenToString()) }
}

/** Sends a word to a dictionary, a translator or a flash card app. */
object Lookup {

    /** The intent that each app with "process text" in its manifest takes. It is what a text selection menu sends. */
    fun probe(): Intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")

    fun intent(word: String, chosen: ComponentName?): Intent {
        val send = probe()
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val target = if (chosen != null) send.setComponent(chosen) else Intent.createChooser(send, null)
        // The panel is not an activity.
        return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
