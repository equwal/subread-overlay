package space.subread.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import space.subread.overlay.core.Transcript
import kotlin.math.abs

/**
 * The panel over the media player: a slim strip of buttons, and the subtitle lines under it at
 * the full width of the panel.
 *
 * The lines are a list. While the player plays, the list follows the line of now: the line of
 * now is the last row, and the panel is only as high as that line. A vertical drag on the lines
 * scrolls back to the lines before, to find what was said. Then the panel grows to show more
 * rows, its bottom edge stays where it is, and the strip shows the "now" button. The button, or
 * a scroll back down to the newest row, makes the list follow the line of now again.
 *
 * A tap on a word selects it and pauses the player at once. A sideways drag makes the selection
 * longer, word by word. When the finger lifts, the selection goes to the dictionary. When the
 * dictionary closes, the selection goes away and the player plays again. A touch that becomes a
 * vertical scroll selects nothing. The system text selection is not used: it needs a window with
 * the input focus, and then the player below gets no keys.
 *
 * With a selection, the strip has "Copy" and "Share", and "Anki" when SubRead Anki is installed.
 *
 * Black on white, and no animation, so that the panel is usable on an e-ink screen.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class OverlayView(context: Context, private val events: Events, anki: Boolean = false) : LinearLayout(context) {

    interface Events {
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
        fun onClose()
        /** A finger is on a word: the player pauses now, before the lookup. */
        fun onTouchWord()
        /** The finger lifted from a selection: the selection goes to the dictionary. */
        fun onLookUp(word: String)
        /** "Share": the selection goes to an app that the user picks in the share sheet. */
        fun onShare(word: String)
        /** "Anki": the selection and its line go to SubRead Anki as a card. */
        fun onAnki(word: String) {}
        /** The play button: pause the player when it plays, else start it. */
        fun onTogglePlay()
        fun onShiftLines(steps: Int)
        fun onNudge(ms: Long)
        /** The selection went away: a tap beside a word, a scroll, or the dictionary closed. */
        fun onSelectionCleared() {}
        /** The touch on a word became a scroll: there is no lookup. */
        fun onTouchScrolled() {}
        /** The selection is now [selection]; null when there is none. */
        fun onSelection(selection: Selection?) {}
        /**
         * The panel grows by [extra] pixels for a read back; 0 when it goes back to its height.
         * Returns how many pixels the top of the panel went up.
         */
        fun onGrow(extra: Int): Int = 0
    }

    /** The selected part [range] of the line [text], which is row [row] of the lines. */
    data class Selection(
        val row: Int,
        val text: String,
        val range: IntRange,
        /** True when the line is a live line of a caption app: it has no times. */
        val live: Boolean = false,
    ) {
        /** The selected words. */
        val words: String get() = text.substring(range.first, range.last + 1)
    }

    private var lines: List<String> = emptyList()
    private var now = -1
    private var around = false
    private var count = 0
    private var textSp = 22f

    /** The selection. It stays when new lines come. */
    var selection: Selection? = null
        private set

    /** The word where a drag started. */
    private var anchor: IntRange? = null

    /** True while the list follows the line of now; false while the user reads back. */
    var following = true
        private set

    /** The height of the list while the user reads back. */
    private var browseHeight = 0
    private var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE

    // The touch on a row: where it started, and true when it makes the selection longer.
    private var downX = 0f
    private var downY = 0f
    private var extending = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** The subtitle lines. */
    val list = Lines()
    private val rows = Rows()

    /** A row that is not on the screen: it measures the height of the rows that the panel follows. */
    private val measurer by lazy { row() }

    /** A word of the app in place of the lines: no player, no file. */
    private val status = TextView(context)
    private val selectionButtons = ArrayList<View>()
    private val nowButton = icon(R.drawable.ic_now, "Back to the line of now") { follow() }
    private val playButton = icon(R.drawable.ic_play, "Start the player") { events.onTogglePlay() }
    private val syncRow = LinearLayout(context)
    private val offsetLabel = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.panel)
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)

        val strip = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        strip.addView(icon(R.drawable.ic_drag, "Move the subtitles") {}.also(::dragWith), iconSize())
        if (anki) selectionButtons += textButton("Anki", "Make an Anki card") { anki() }
        selectionButtons += textButton("Copy", null) { copy() }
        selectionButtons += textButton("Share", null) { share() }
        for (button in selectionButtons) {
            button.visibility = GONE
            strip.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, dp(STRIP_DP)))
        }
        strip.addView(View(context), LayoutParams(0, 0, 1f))
        nowButton.visibility = GONE
        strip.addView(nowButton, iconSize())
        strip.addView(playButton, iconSize())
        strip.addView(icon(R.drawable.ic_timing, "Timing") { syncRow.isVisible = !syncRow.isVisible }, iconSize())
        strip.addView(icon(R.drawable.ic_close, "Close the subtitles") { events.onClose() }, iconSize())
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list.adapter = rows
        list.divider = null
        list.dividerHeight = 0
        list.selector = Color.TRANSPARENT.toDrawable()
        // No scroll bar and no glow at the ends: they move, and an e-ink screen shows each move.
        list.isVerticalScrollBarEnabled = false
        list.overScrollMode = OVER_SCROLL_NEVER
        // The newest row is at the bottom. While the list follows, a new line jumps it there.
        list.isStackFromBottom = true
        list.transcriptMode = AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrollState = state
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE && !following && atEnd()) follow()
            }

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                // Only a scroll of the user starts a read back, not a new line.
                if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE && following && !atEnd()) browse()
            }
        })
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.setTextColor(Color.BLACK)
        status.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
        status.setPadding(dp(8), dp(2), dp(8), dp(4))
        status.visibility = GONE
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        syncRow.gravity = Gravity.CENTER
        syncRow.addView(textButton("◀ line", "The line before is the line now") { events.onShiftLines(-1) })
        syncRow.addView(textButton("−0.5 s", "Subtitles later") { events.onNudge(-500) })
        offsetLabel.setTextColor(Color.BLACK)
        offsetLabel.setPadding(dp(8), 0, dp(8), 0)
        syncRow.addView(offsetLabel)
        syncRow.addView(textButton("+0.5 s", "Subtitles sooner") { events.onNudge(500) })
        syncRow.addView(textButton("line ▶", "The next line is the line now") { events.onShiftLines(1) })
        syncRow.visibility = GONE
        addView(syncRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTextSize(sp: Float) {
        textSp = sp
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        rows.notifyDataSetChanged()
    }

    /** Lets [percent] of the player show through the panel. The text and the buttons stay as they are. */
    fun setTransparency(percent: Int) {
        background.mutate().alpha = 255 * (100 - percent.coerceIn(0, 100)) / 100
    }

    fun showPlaying(playing: Boolean) {
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = if (playing) "Pause the player" else "Start the player"
    }

    fun showOffset(ms: Long) {
        offsetLabel.text = "%+.1f s".format(ms / 1000.0)
    }

    /**
     * Shows [lines] up to the line of now, [now], and the line after it too with [around]. The
     * caller gives the same list object while the subtitle file stays, so a call for the same
     * line changes nothing.
     */
    fun showLines(lines: List<String>, now: Int, around: Boolean) {
        if (lines === this.lines && now == this.now && around == this.around && list.isVisible) return
        this.lines = lines
        this.now = now
        this.around = around
        count = Transcript.rowCount(lines.size, now, around)
        status.visibility = GONE
        list.visibility = VISIBLE
        val kept = selection
        if (kept != null && (kept.row >= count || lines[kept.row] != kept.text)) select(kept.row, null)
        // The list binds the rows again. While it follows, it jumps to the newest row, and its
        // height is the height of the line of now.
        rows.notifyDataSetChanged()
    }

    /** Shows a word of the app to the user, where the lines would be: no player, no file. */
    fun showStatus(value: String) {
        follow()
        select(-1, null)
        lines = emptyList()
        now = -1
        count = 0
        rows.notifyDataSetChanged()
        list.visibility = GONE
        status.visibility = VISIBLE
        status.text = value
    }

    /** Removes the selection: the dictionary closed. */
    fun clearSelection() = select(-1, null)

    /** The view of [row] while it is on the screen, else null. For tests. */
    fun rowView(row: Int): TextView? = list.getChildAt(row - list.firstVisiblePosition) as? TextView

    /** Selects the word at the point [x], [y] of the view of [row]. For a tap, and for tests. */
    fun selectAt(row: Int, x: Float, y: Float, extend: Boolean = false) {
        val view = rowView(row) ?: return
        val text = lines.getOrNull(row) ?: return
        val offset = offsetAt(view, text, x, y)
        if (offset == null) {
            if (!extend) select(row, null)
            return
        }
        val from = anchor
        if (extend && from != null && selection?.row == row) {
            select(row, Words.between(text, from, offset))
        } else {
            anchor = Words.at(text, offset)
            select(row, anchor)
        }
    }

    private fun select(row: Int, range: IntRange?) {
        val before = selection
        selection = if (range == null) null else Selection(row, lines[row], range)
        if (range == null) anchor = null
        for (button in selectionButtons) button.isVisible = range != null
        // The marks change, not the size of a row: the rows on the screen draw again, with no new layout.
        for (i in 0 until list.childCount) (list.getChildAt(i) as? TextView)?.let { bind(it, list.firstVisiblePosition + i) }
        if (selection != before) events.onSelection(selection)
        if (range == null && before != null) events.onSelectionCleared()
    }

    /** The index of the character under the point; null when the point is not on a character. */
    private fun offsetAt(view: TextView, text: String, x: Float, y: Float): Int? {
        val layout = view.layout ?: return null
        if (text.isEmpty()) return null
        val inX = x - view.totalPaddingLeft
        val inY = y - view.totalPaddingTop
        if (inY < 0 || inY > layout.height) return null
        val line = layout.getLineForVertical(inY.toInt())
        if (inX < layout.getLineLeft(line) || inX > layout.getLineRight(line)) return null
        // The offset of a point is a place between two characters. The character under the
        // finger is the one before that place when the finger is on its right half.
        val between = layout.getOffsetForHorizontal(line, inX)
        val under = if (between > layout.getLineStart(line) && layout.getPrimaryHorizontal(between) > inX) between - 1 else between
        return under.coerceIn(0, text.length - 1)
    }

    private fun selected(): String? = selection?.words

    private fun lookUp() {
        events.onLookUp(selected() ?: return)
    }

    private fun share() {
        events.onShare(selected() ?: return)
    }

    private fun anki() {
        events.onAnki(selected() ?: return)
    }

    private fun copy() {
        val word = selected() ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("subtitle", word))
    }

    /** The user scrolled away from the newest row: the list stops to follow, and the panel grows. */
    private fun browse() {
        following = false
        list.transcriptMode = AbsListView.TRANSCRIPT_MODE_DISABLED
        nowButton.visibility = VISIBLE
        val old = list.height
        browseHeight = maxOf(old, browseMinHeight())
        val up = events.onGrow(browseHeight - old)
        // The top of the list went up on the screen: the rows move down in the list by the same
        // distance, so that they stay under the finger.
        list.setSelectionFromTop(list.firstVisiblePosition, (list.getChildAt(0)?.top ?: 0) + up)
        list.requestLayout()
    }

    /** The list follows the line of now again, and the panel goes back to its height and place. */
    private fun follow() {
        if (following) return
        following = true
        nowButton.visibility = GONE
        list.transcriptMode = AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        events.onGrow(0)
        rows.notifyDataSetChanged()
    }

    /** True when the newest row is on the screen from its top to its bottom. */
    private fun atEnd(): Boolean {
        if (count == 0) return true
        if (list.lastVisiblePosition < count - 1) return false
        val last = list.getChildAt(list.childCount - 1) ?: return true
        return last.bottom <= list.height - list.paddingBottom
    }

    /** The height of the rows that the list follows: the line of now, and the lines around it. */
    private fun followHeight(width: Int): Int {
        if (width <= 0) return 0
        var height = 0
        for (i in Transcript.followRows(count, now, around)) {
            bind(measurer, i)
            measurer.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            height += measurer.measuredHeight
        }
        return height
    }

    /** The least height of the list while the user reads back: [BROWSE_LINES] lines of text. */
    private fun browseMinHeight(): Int {
        measurer.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        return BROWSE_LINES * measurer.lineHeight + measurer.paddingTop + measurer.paddingBottom + list.paddingTop + list.paddingBottom
    }

    /** Draws line [position] in [view]: the line of now black, the other lines small and grey, the selection white on black. */
    private fun bind(view: TextView, position: Int) {
        view.tag = position
        val text = lines.getOrNull(position).orEmpty()
        val isNow = position == now.coerceAtMost(lines.size - 1)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isNow) textSp else textSp * AROUND_SIZE)
        view.setTextColor(if (isNow) Color.BLACK else Color.DKGRAY)
        val range = selection?.takeIf { it.row == position }?.range
        if (range == null) {
            view.text = text
            return
        }
        val marked = SpannableString(text)
        marked.setSpan(BackgroundColorSpan(Color.BLACK), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        marked.setSpan(ForegroundColorSpan(Color.WHITE), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = marked
    }

    @SuppressLint("ClickableViewAccessibility") // The buttons of the strip do the same for a screen reader.
    private fun row() = TextView(context).apply {
        typeface = Typeface.DEFAULT
        setPadding(dp(8), dp(2), dp(8), dp(2))
        setLineSpacing(0f, 1.15f)
        layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        setOnTouchListener { view, event ->
            touch(view as TextView, event)
            true
        }
    }

    /**
     * A touch on a row. Down selects the word and pauses the player. A sideways move makes the
     * selection longer: the list then does not scroll. A vertical move is a scroll: the list
     * takes the finger, and the row gets a cancel, so the selection goes away. Up looks up.
     */
    private fun touch(view: TextView, event: MotionEvent) {
        val row = view.tag as? Int ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                extending = false
                selectAt(row, event.x, event.y)
                if (selection != null) events.onTouchWord()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                if (!extending && selection != null && dx > slop && dx > abs(event.y - downY)) {
                    extending = true
                    list.requestDisallowInterceptTouchEvent(true)
                }
                if (extending) selectAt(row, event.x, event.y, extend = true)
            }
            MotionEvent.ACTION_UP -> lookUp()
            MotionEvent.ACTION_CANCEL -> if (selection != null) {
                select(row, null)
                events.onTouchScrolled()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // A drag has no equal for a screen reader; the panel works where it is.
    private fun dragWith(handle: View) {
        var lastX = 0f
        var lastY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    events.onDrag(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> events.onDragEnd()
            }
            true
        }
    }

    /** A small black icon with no frame. It is grey only while the finger is on it. */
    private fun icon(drawable: Int, description: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable)
        setBackgroundResource(R.drawable.button)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        contentDescription = description
        setOnClickListener { onClick() }
    }

    private fun iconSize() = LayoutParams(dp(40), dp(STRIP_DP))

    /** A small black word with no frame, the same as the icons. */
    private fun textButton(label: String, description: String?, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setBackgroundResource(R.drawable.button)
        stateListAnimator = null
        minWidth = dp(36)
        minimumWidth = dp(36)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(6), dp(10), dp(6))
        contentDescription = description ?: label
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** The subtitle lines. The height follows the line of now, or is fixed while the user reads back. */
    inner class Lines : ListView(this@OverlayView.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = if (following) followHeight(width - paddingLeft - paddingRight) + paddingTop + paddingBottom else browseHeight
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    /** The rows of the list: one line each, from the first line to the line of now or the line after it. */
    private inner class Rows : BaseAdapter() {
        override fun getCount() = this@OverlayView.count
        override fun getItem(position: Int): String = lines[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            ((convertView as? TextView) ?: row()).also { bind(it, position) }
    }

    private companion object {
        /** The height of the strip of buttons, in dp. */
        const val STRIP_DP = 36

        /** The lines before and after the line of now are this much of its size. */
        const val AROUND_SIZE = 0.85f

        /** While the user reads back, the list shows at least this many lines of text. */
        const val BROWSE_LINES = 4
    }
}
