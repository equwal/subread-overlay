package space.subread.overlay

import android.icu.text.BreakIterator

/** Finds the word under a finger. */
object Words {

    /**
     * The range of the word of [text] that has the character at [offset]; null on a space or a
     * punctuation mark.
     *
     * ICU cuts Japanese, Chinese and Thai with a dictionary, and other scripts at spaces and
     * punctuation. The cut is a good first try, not a parse: the user can drag to make the
     * selection longer.
     */
    fun at(text: CharSequence, offset: Int): IntRange? {
        if (offset !in text.indices) return null
        val words = BreakIterator.getWordInstance()
        words.setText(text.toString())
        val end = words.following(offset)
        if (end == BreakIterator.DONE) return null
        val isWord = words.ruleStatus != BreakIterator.WORD_NONE
        val start = words.previous()
        return if (isWord && start != BreakIterator.DONE) start until end else null
    }

    /** The range from the word at [anchor] to the word at [offset], in the order of the text. */
    fun between(text: CharSequence, anchor: IntRange, offset: Int): IntRange {
        val other = at(text, offset) ?: return anchor
        return minOf(anchor.first, other.first)..maxOf(anchor.last, other.last)
    }
}
