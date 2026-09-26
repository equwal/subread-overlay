package space.subread.overlay.core

/**
 * Decides when the player plays again after a touch on a word.
 *
 * A touch on a word pauses the player at once. The player plays again when the look-up ends:
 * the dictionary closes, or the touch becomes a scroll while no dictionary is open. It plays
 * again only when a touch paused it: a player that the user paused stays paused. The play
 * button of the panel gives the player back to the user, so the end of the look-up then does
 * not start it.
 */
class LookUpPause {

    /** True when a touch paused the player, and the player did not play again yet. */
    private var paused = false

    /** True while the dictionary is open. */
    private var open = false

    /** A finger is on a word. [pausedNow] is true when the player played and pauses now. */
    fun touched(pausedNow: Boolean) {
        if (pausedNow) paused = true
    }

    /** The finger lifted: the dictionary opens. */
    fun opened() {
        open = true
    }

    /** The touch became a scroll, so there is no look-up. True when the player must play again. */
    fun scrolled(): Boolean = !open && release()

    /** The dictionary closed. True when the player must play again. */
    fun closed(): Boolean {
        open = false
        return release()
    }

    /** The user pressed the play button of the panel. */
    fun toggled() {
        paused = false
    }

    private fun release(): Boolean = paused.also { paused = false }
}
