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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * The panel over the media player: the subtitle line, and a few buttons.
 *
 * A tap on the line selects the word under the finger. A drag makes the selection longer, word
 * by word. The selection goes to a dictionary with "Look up". The system text selection is not
 * used: it needs a window with the input focus, and then the player below gets no keys.
 *
 * Black on white and nothing that moves, so that the panel is usable on an e-ink screen.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class OverlayView(context: Context, private val events: Events) : LinearLayout(context) {

    interface Events {
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
        fun onClose()
        fun onLookUp(word: String)
        fun onShiftLines(steps: Int)
        fun onNudge(ms: Long)
    }

    /** The line on the panel, without the selection marks. */
    var line: String = ""
        private set

    /** The part of [line] that is selected. */
    var selection: IntRange? = null
        private set

    val text = TextView(context)
    private val lookUpRow = LinearLayout(context)
    private val syncRow = LinearLayout(context)
    private val offsetLabel = TextView(context)
    private var anchor: IntRange? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.panel)
        val pad = dp(6)
        setPadding(pad, pad, pad, pad)

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("≡", "Move the subtitles") {}.also(::dragWith), wrap())
        text.setTextColor(Color.BLACK)
        text.typeface = Typeface.DEFAULT
        text.setPadding(dp(8), dp(4), dp(8), dp(4))
        text.setLineSpacing(0f, 1.15f)
        selectWith(text)
        top.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("⋯", "Timing") { toggle(syncRow) }, wrap())
        top.addView(button("✕", "Close the subtitles") { events.onClose() }, wrap())
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        lookUpRow.gravity = Gravity.END
        lookUpRow.addView(button("Copy", null) { copy() }, wrap())
        lookUpRow.addView(button("Look up", null) { lookUp() }, wrap())
        lookUpRow.visibility = View.GONE
        addView(lookUpRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        syncRow.gravity = Gravity.CENTER
        syncRow.addView(button("◀ line", "The line before is the line now") { events.onShiftLines(-1) }, wrap())
        syncRow.addView(button("−0.5 s", "Subtitles later") { events.onNudge(-500) }, wrap())
        offsetLabel.setTextColor(Color.BLACK)
        offsetLabel.setPadding(dp(8), 0, dp(8), 0)
        syncRow.addView(offsetLabel, wrap())
        syncRow.addView(button("+0.5 s", "Subtitles sooner") { events.onNudge(500) }, wrap())
        syncRow.addView(button("line ▶", "The next line is the line now") { events.onShiftLines(1) }, wrap())
        syncRow.visibility = View.GONE
        addView(syncRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTextSize(sp: Float) = text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)

    fun showOffset(ms: Long) {
        offsetLabel.text = "%+.1f s".format(ms / 1000.0)
    }

    /** Shows a subtitle line. The selection goes away when the line changes. */
    fun showLine(value: String) {
        if (value == line) return
        line = value
        text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        select(null)
    }

    /** Shows a word of the app to the user, where the line would be: no player, no file. */
    fun showStatus(value: String) {
        line = ""
        selection = null
        lookUpRow.visibility = View.GONE
        text.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
        text.text = value
    }

    /** Selects the word at the point [x], [y] of the text view. For a tap, and for tests. */
    fun selectAt(x: Float, y: Float, extend: Boolean = false) {
        val offset = offsetAt(x, y) ?: return select(if (extend) selection else null)
        val from = anchor
        if (extend && from != null) {
            select(Words.between(line, from, offset))
        } else {
            anchor = Words.at(line, offset)
            select(anchor)
        }
    }

    private fun select(range: IntRange?) {
        selection = range
        if (range == null) anchor = null
        val marked = SpannableString(line)
        if (range != null) {
            marked.setSpan(BackgroundColorSpan(Color.BLACK), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            marked.setSpan(ForegroundColorSpan(Color.WHITE), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = marked
        lookUpRow.visibility = if (range == null) View.GONE else View.VISIBLE
    }

    /** The index of the character under the point; null when the point is not on a character. */
    private fun offsetAt(x: Float, y: Float): Int? {
        val layout = text.layout ?: return null
        if (line.isEmpty()) return null
        val inX = x - text.totalPaddingLeft
        val inY = y - text.totalPaddingTop
        if (inY < 0 || inY > layout.height) return null
        val row = layout.getLineForVertical(inY.toInt())
        if (inX < layout.getLineLeft(row) || inX > layout.getLineRight(row)) return null
        // The offset of a point is a place between two characters. The character under the
        // finger is the one before that place when the finger is on its right half.
        val between = layout.getOffsetForHorizontal(row, inX)
        val under = if (between > layout.getLineStart(row) && layout.getPrimaryHorizontal(between) > inX) between - 1 else between
        return under.coerceIn(0, line.length - 1)
    }

    private fun selected(): String? = selection?.let { line.substring(it.first, it.last + 1) }

    private fun lookUp() {
        events.onLookUp(selected() ?: return)
    }

    private fun copy() {
        val word = selected() ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("subtitle", word))
    }

    @SuppressLint("ClickableViewAccessibility") // The buttons of the panel do the same for a screen reader.
    private fun selectWith(view: TextView) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> selectAt(event.x, event.y)
                MotionEvent.ACTION_MOVE -> selectAt(event.x, event.y, extend = true)
            }
            true
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

    private fun toggle(row: View) {
        row.isVisible = !row.isVisible
    }

    private fun button(label: String, description: String?, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        contentDescription = description ?: label
        minWidth = dp(44)
        minimumWidth = dp(44)
        setOnClickListener { onClick() }
    }

    private fun wrap() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
