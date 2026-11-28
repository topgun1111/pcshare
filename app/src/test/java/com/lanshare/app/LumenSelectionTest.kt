package com.lanshare.app

import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LumenSelectionTest {
    @Test fun checkboxRowHasLabelAndKeepsItsExistingClick() {
        val row = LinearLayout(RuntimeEnvironment.getApplication())
        var selections = 0
        row.setOnClickListener { selections++ }

        LumenSelection.row(row, "Folders first", checked = true)

        val info = row.createAccessibilityNodeInfo()
        assertEquals(CheckBox::class.java.name, info.className)
        assertEquals("Folders first", info.text)
        assertTrue(info.isCheckable)
        assertTrue(info.isChecked)
        assertTrue(row.isFocusable)
        assertTrue(row.performClick())
        assertEquals(1, selections)
    }

    @Test fun radioRowExposesItsCheckedState() {
        val row = LinearLayout(RuntimeEnvironment.getApplication())
        LumenSelection.row(row, "Grid", checked = true, radio = true)

        val info = row.createAccessibilityNodeInfo()
        assertEquals(RadioButton::class.java.name, info.className)
        assertEquals("Grid", info.text)
        assertTrue(info.isCheckable)
        assertTrue(info.isChecked)
    }
}
