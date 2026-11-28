package com.lanshare.app

import android.app.Application
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LumenNavigationTest {
    @Test fun fileRoleUsesFullNameAndOffersTheExistingActionFromTheKeyboard() {
        val app = RuntimeEnvironment.getApplication()
        val cfg = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("tr")) }
        val row = View(app.createConfigurationContext(cfg))
        val name = "Çok uzun dosya adı.pdf"
        var actions = 0
        row.setOnLongClickListener { actions++; true }
        NlA11y.attachFileRow(row)
        NlA11y.apply(row, item(name), true)

        val node = row.createAccessibilityNodeInfo()
        assertEquals(Button::class.java.name, node.className)
        assertTrue(node.contentDescription.toString().startsWith("$name, dosya"))
        assertTrue(node.isSelected)
        assertEquals("Seçili", node.stateDescription)
        val options = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F10, 0, KeyEvent.META_SHIFT_ON)
        assertTrue(row.dispatchKeyEvent(options))
        assertEquals(1, actions)
    }

    @Test fun recycledSearchHeadingClearsSelectionAndDoesNotOfferFileActions() {
        val row = View(RuntimeEnvironment.getApplication())
        NlA11y.attachFileRow(row)
        NlA11y.apply(row, item("Previous file"), true)
        NlA11y.apply(row, item("In subfolders", "hdr"), false)

        val node = row.createAccessibilityNodeInfo()
        assertEquals(TextView::class.java.name, node.className)
        assertTrue(node.isHeading)
        assertFalse(node.isSelected)
        assertFalse(node.isLongClickable)
        assertFalse(row.isFocusable)
    }

    private fun item(name: String, kind: String = "file") = NlRow(
        nm = name, dir = false, k = kind, a = "", b = "",
        badge = null, badgeCol = 0, dup = false, path = null, size = 0, mtime = 0
    )
}
