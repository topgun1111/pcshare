package com.lanshare.app

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Lumen Part 4a (A1): TalkBack text for the native list. Rows are single canvas-drawn views, so the whole description goes on the view:
 * "name, folder|file, details, hidden, duplicate"; a selected row says "Selected" through stateDescription; day headings are announced as headings.
 */
object NlA11y {
    fun describe(c: Context, r: NlRow): String {
        if (r.k == "hdr") return r.nm
        val sb = StringBuilder(r.nm)
        sb.append(", ").append(c.getString(if (r.dir) R.string.lumen_a11y_folder else R.string.lumen_a11y_file))
        if (r.a.isNotBlank()) sb.append(", ").append(r.a.trim())
        if (r.b.isNotBlank()) sb.append(", ").append(r.b.trim())
        if (r.hid) sb.append(", ").append(c.getString(R.string.lumen_a11y_hidden))
        if (r.dup) sb.append(", ").append(c.getString(R.string.lumen_a11y_duplicate))
        return sb.toString()
    }

    internal fun optionsKey(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_F10 && event.isShiftPressed)

    internal fun attachFileRow(row: View) {
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = if (ViewCompat.isAccessibilityHeading(host)) TextView::class.java.name else Button::class.java.name
                if (host.isLongClickable) {
                    info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK)
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                        host.context.getString(R.string.lumen_a11y_selection_actions)
                    ))
                }
            }
        })
        row.setOnKeyListener { view, _, event ->
            view.isLongClickable && optionsKey(event) && view.performLongClick()
        }
    }

    fun apply(v: View, r: NlRow, selected: Boolean) {
        val heading = r.k == "hdr"
        v.contentDescription = describe(v.context, r)
        v.isSelected = selected && !heading
        v.isClickable = !heading
        v.isLongClickable = !heading && !r.hit
        v.isFocusable = !heading
        ViewCompat.setStateDescription(v, if (v.isSelected) v.context.getString(R.string.lumen_a11y_selected) else null)
        ViewCompat.setAccessibilityHeading(v, heading)
    }

    /** Marks a screen / section title so TalkBack can jump between headings. */
    fun <T : View> heading(v: T): T { ViewCompat.setAccessibilityHeading(v, true); return v }

    /** Spoken names for the one-symbol buttons of the native screens (a screen reader reads "←" or "⋮" badly or not at all). */
    private val GLYPHS = mapOf(
        "←" to "Back", "✓" to "Save", "⋮" to "More options", "✕" to "Close",
        "▲" to "Previous match", "▼" to "Next match", "↶" to "Undo", "↷" to "Redo",
        "\uD83C\uDFA8" to "Text colour", "\uD83D\uDC41" to "Preview", "\uD83D\uDDA8" to "Print",
        "▦" to "Pages", "☾" to "Night mode", "↗" to "Open with another app", "⤴" to "Share", "Aa" to "Text size",
        "×" to "Close", "✖" to "Close", "…" to "More options", "↺" to "Rotate left", "↻" to "Rotate right",
        "⟲" to "Reset", "⤢" to "Resize", "+" to "Increase", "−" to "Decrease"
    )

    /** Walks a native screen once: a clickable view that is not already a button announces itself as "Button", and a symbol-only
     * button without a description gets its spoken name. Skips the WebView and every view that has its own accessibility delegate
     * (the native list, bars and drawer describe themselves). */
    fun polish(root: View) {
        if (root is WebView || root.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
        // Only label actual controls; never replace an explicit label or custom virtual-node delegate.
        if (root.isClickable && root is TextView && root !is EditText && root !is CompoundButton &&
            root.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO) {
            if (root.contentDescription.isNullOrEmpty()) root.text?.toString()?.trim()?.let { GLYPHS[it] }?.let { root.contentDescription = it }
            if (root !is Button && !ViewCompat.hasAccessibilityDelegate(root)) {
                root.isFocusable = true
                ViewCompat.setAccessibilityDelegate(root, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name
                    }
                })
            }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) polish(root.getChildAt(i))
    }
}
