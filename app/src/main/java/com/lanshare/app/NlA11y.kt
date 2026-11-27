package com.lanshare.app

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
    fun describe(r: NlRow): String {
        if (r.k == "hdr") return r.nm
        val sb = StringBuilder(r.nm)
        sb.append(if (r.dir) ", folder" else ", file")
        if (r.a.isNotBlank()) sb.append(", ").append(r.a.trim())
        if (r.b.isNotBlank()) sb.append(", ").append(r.b.trim())
        if (r.hid) sb.append(", hidden")
        if (r.dup) sb.append(", duplicate")
        return sb.toString()
    }

    fun apply(v: View, r: NlRow, selected: Boolean) {
        v.contentDescription = describe(r)
        ViewCompat.setStateDescription(v, if (selected) "Selected" else null)
        ViewCompat.setAccessibilityHeading(v, r.k == "hdr")
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
