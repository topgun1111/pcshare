package com.lanshare.app

import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LumenFileNameTest {
    private val paint = TextPaint().apply {
        textSize = 16f
        typeface = Typeface.MONOSPACE
    }

    @Test fun reservesSpaceForFinalExtension() {
        val name = "a-very-long-report.final.pdf"
        val width = paint.measureText("a-very….pdf")
        val display = ellipsizeFileName(name, false, paint, width)

        assertTrue(display.startsWith("a-"))
        assertTrue(display.endsWith("….pdf"))
        assertTrue(paint.measureText(display) <= width)
    }

    @Test fun keepsShortNamesAndUsesEndEllipsisForFoldersAndExtensionlessNames() {
        val short = "short.txt"
        assertEquals(short, ellipsizeFileName(short, false, paint, paint.measureText(short)))
        val width = paint.measureText("prefix…")
        for ((name, directory) in listOf(
            "prefix.folder" to true, ".hidden-file" to false,
            "extensionless" to false, "trailing-dot." to false
        )) {
            val expected = TextUtils.ellipsize(name, paint, width, TextUtils.TruncateAt.END).toString()
            assertEquals(expected, ellipsizeFileName(name, directory, paint, width))
        }
    }

    @Test fun keepsTheTailWhenTheFullExtensionCannotFit() {
        val name = "report.extremely-long-extension"
        val width = paint.measureText("…sion")
        assertEquals("…sion", ellipsizeFileName(name, false, paint, width))
        assertEquals("", ellipsizeFileName(name, false, paint, 0f))
        assertEquals("", ellipsizeFileName("", false, paint, width))
    }
}
