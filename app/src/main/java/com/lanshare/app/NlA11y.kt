package com.lanshare.app

import android.view.View
import androidx.core.view.ViewCompat

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
}
