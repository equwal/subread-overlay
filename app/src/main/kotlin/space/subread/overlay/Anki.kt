package space.subread.overlay

import android.content.Context
import android.content.Intent

/**
 * Sends a card to SubRead Anki: the word and the subtitle line it is in. The strings are the
 * intent contract of that app, see its docs/intent-api.md. SubRead Anki looks the word up in
 * SubRead Dictionary, takes a screenshot of the player, reads the line with the voice of the
 * device, and puts the card in AnkiDroid.
 */
object Anki {
    const val PACKAGE = "space.subread.anki"
    const val INSTALL = "https://github.com/equwal/subread-anki/releases/latest"
    private const val ACTION_ADD = "space.subread.anki.action.ADD"
    private const val EXTRA_WORD = "space.subread.anki.extra.WORD"
    private const val EXTRA_SENTENCE = "space.subread.anki.extra.SENTENCE"
    private const val EXTRA_SOURCE = "space.subread.anki.extra.SOURCE"
    private const val EXTRA_SCREENSHOT = "space.subread.anki.extra.SCREENSHOT"

    fun installed(context: Context): Boolean =
        context.packageManager.resolveActivity(Intent(ACTION_ADD).setPackage(PACKAGE), 0) != null

    /** The intent for one card. The panel is not an activity, so the intent starts a new task. */
    fun intent(word: String, line: String, source: String): Intent = Intent(ACTION_ADD)
        .setPackage(PACKAGE)
        .putExtra(EXTRA_WORD, word)
        .putExtra(EXTRA_SENTENCE, line)
        .putExtra(EXTRA_SOURCE, source)
        .putExtra(EXTRA_SCREENSHOT, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
