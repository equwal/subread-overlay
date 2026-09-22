package space.subread.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import space.subread.overlay.core.Srt

/**
 * The one screen of the app: the two permissions, the subtitle file, the dictionary, the text
 * size, and the button for the panel. Each part is on the screen from the start, so that the
 * user sees what the app does before a button is pressed.
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(content)
        })
    }

    /** The user comes back from the settings of Android here, so the state is read again. */
    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun draw() {
        content.removeAllViews()
        val overlay = Settings.canDrawOverlays(this)
        val listener = MediaListener.isAllowed(this)

        title(getString(R.string.app_name))
        note(getString(R.string.about))
        note(getString(R.string.privacy))

        step(R.string.step_overlay, getString(R.string.step_overlay_why), if (overlay) null else getString(R.string.allow), overlay) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        }
        step(R.string.step_listener, getString(R.string.step_listener_why), if (listener) null else getString(R.string.allow), listener) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        step(R.string.step_file, store.subtitlesName ?: getString(R.string.step_file_none), getString(R.string.choose)) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                PICK_SUBTITLES,
            )
        }
        step(R.string.step_dictionary, dictionaryName(), getString(R.string.choose)) { chooseDictionary() }
        step(R.string.step_size, "${store.textSizeSp.toInt()} sp", null) {}
        row(button("−") { size(-2f) }, button("+") { size(2f) })
        step(R.string.step_transparency, getString(R.string.step_transparency_why), null) {}
        content.addView(transparencySlider(), wide())
        val around = store.linesAround
        step(
            R.string.step_lines,
            getString(if (around) R.string.lines_three else R.string.lines_one),
            getString(if (around) R.string.lines_show_one else R.string.lines_show_three),
        ) {
            store.linesAround = !around
            MediaListener.instance?.reload()
            draw()
        }

        val shown = store.shown && MediaListener.instance != null
        content.addView(button(getString(if (shown) R.string.hide else R.string.show)) { togglePanel() }.apply {
            setTypeface(typeface, Typeface.BOLD)
        }, wide(top = 24))

        note(getString(R.string.make_subtitles), top = 32)
        val subread = packageManager.getLaunchIntentForPackage(SUBREAD_PACKAGE)
        if (subread != null) {
            content.addView(button(getString(R.string.open_subread)) { runCatching { startActivity(subread) } }, wide())
        } else {
            content.addView(button(getString(R.string.install_subread)) { open(SUBREAD_INSTALL) }, wide())
        }
        if (BuildConfig.DONATE_LINK) content.addView(button(getString(R.string.donate)) { open(KOFI) }, wide())
    }

    private fun togglePanel() {
        val service = MediaListener.instance
        when {
            !Settings.canDrawOverlays(this) || !MediaListener.isAllowed(this) -> toast(R.string.need_both)
            service == null -> toast(R.string.listener_starting)
            store.shown -> service.hidePanel()
            else -> service.showPanel()
        }
        draw()
    }

    private fun size(change: Float) {
        store.textSizeSp += change
        MediaListener.instance?.reload()
        draw()
    }

    /** A slider from 0 % (a white panel) to 90 %. The panel changes while the finger moves. */
    @SuppressLint("SetTextI18n")
    private fun transparencySlider(): View {
        val label = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 15f
        }
        val slider = SeekBar(this).apply {
            max = 90
            progress = store.transparencyPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    label.text = "$value %"
                    if (fromUser) {
                        store.transparencyPercent = value
                        MediaListener.instance?.reload()
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        label.text = "${slider.progress} %"
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        }
    }

    private fun dictionaryName(): String {
        val chosen = store.dictionary ?: return getString(R.string.dictionary_ask)
        return dictionaries().firstOrNull { it.second == chosen }?.first ?: getString(R.string.dictionary_ask)
    }

    /** The apps that take a selected text, with the name that each one gives the entry. */
    private fun dictionaries(): List<Pair<String, ComponentName>> =
        packageManager.queryIntentActivities(Lookup.probe(), 0).map {
            val app = it.activityInfo.applicationInfo.loadLabel(packageManager)
            val entry = it.loadLabel(packageManager)
            val name = if (entry.toString() == app.toString()) "$app" else "$app: $entry"
            name to ComponentName(it.activityInfo.packageName, it.activityInfo.name)
        }.sortedBy { it.first }

    private fun chooseDictionary() {
        val found = dictionaries()
        if (found.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.dictionary_none).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val names = listOf(getString(R.string.dictionary_ask)) + found.map { it.first }
        AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, which ->
            store.dictionary = found.getOrNull(which - 1)?.second
            draw()
        }.show()
    }

    @Deprecated("The platform Activity has no other result API, and this app has no AndroidX activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != PICK_SUBTITLES || resultCode != RESULT_OK || uri == null) return
        // The listener reads the file later, also after a restart of the device.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val lines = runCatching {
            contentResolver.openInputStream(uri)!!.use { Srt.parse(it.readBytes().decodeToString()) }.size
        }.getOrDefault(0)
        store.subtitles = uri
        store.subtitlesName = resources.getQuantityString(R.plurals.step_file_cues, lines, displayName(uri), lines)
        MediaListener.instance?.reload()
        draw()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    private fun open(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    // The screen is built in code: six rows do not need a layout file each.

    private fun title(value: String) = content.addView(TextView(this).apply {
        text = value
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
    })

    private fun note(value: String, top: Int = 8) = content.addView(TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(Color.BLACK)
    }, wide(top))

    @SuppressLint("SetTextI18n")
    private fun step(name: Int, detail: String, action: String?, done: Boolean = false, onAction: () -> Unit) {
        content.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) })
        content.addView(TextView(this).apply {
            text = getString(name) + if (done) "  ✓ ${getString(R.string.allowed)}" else ""
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }, wide(top = 12))
        note(detail, top = 4)
        if (action != null) content.addView(button(action, onAction), LinearLayout.LayoutParams(-2, -2))
    }

    private fun row(vararg views: View) = content.addView(LinearLayout(this).apply { views.forEach { addView(it) } })

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun wide(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_SUBTITLES = 1
        const val SUBREAD_PACKAGE = "space.subread.app"
        // The release page of the SubRead app. Each release has the APK as SubRead.apk.
        const val SUBREAD_INSTALL = "https://github.com/equwal/subread-android/releases/latest"
        const val KOFI = "https://ko-fi.com/truex"
    }
}
