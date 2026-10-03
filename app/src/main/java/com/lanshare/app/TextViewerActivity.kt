package com.lanshare.app

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Native text / source-code viewer. The browser hands it ONE file as a URL of the app's own server (/api/dl), so files on this
 * phone, on other devices and on SMB shares all open the same way. Shows the first 1 MB (UTF-8, UTF-16 with BOM, otherwise the
 * Turkish Windows code page), selectable text, three font sizes, wrap on / off.
 */
class TextViewerActivity : Activity() {
    companion object {
        /** JSON handed over by BrowserActivity: {name, url, size} */
        @Volatile var pending: String? = null
        private const val MAX = 1 shl 20
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var body: TextView
    private lateinit var info: TextView
    private lateinit var spin: ProgressBar
    private lateinit var hscroll: HorizontalScrollView
    private var wrap = true
    private var sizeIdx = 1
    private var gone = false
    private val sizes = floatArrayOf(12f, 14.5f, 18f)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val json = pending
        pending = null
        val o = try { if (json == null) null else JSONObject(json) } catch (_: Exception) { null }
        val url = o?.optString("url").orEmpty()
        if (o == null || !(url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) { finish(); return }   // only the app's own server
        val name = o.optString("name")
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cBg = if (night) 0xFF121314.toInt() else 0xFFFFFFFF.toInt()
        val cFg = if (night) 0xFFE6E6E6.toInt() else 0xFF1B1B1B.toInt()
        val cMut = if (night) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF1C1C1E.toInt()) }
        fun btn(t: String, sp: Float, click: () -> Unit) = TextView(this).apply {
            text = t; textSize = sp; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(14), 0, dp(14), 0); setOnClickListener { click() }
        }
        top.addView(btn("\u2190", 22f) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        top.addView(TextView(this).apply {
            text = name; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(btn("\u21B5", 20f) { setWrap(!wrap) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        top.addView(btn("Aa", 16f) { sizeIdx = (sizeIdx + 1) % sizes.size; body.textSize = sizes[sizeIdx] }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        info = TextView(this).apply { textSize = 12f; setTextColor(cMut); setPadding(dp(14), dp(6), dp(14), dp(6)); visibility = android.view.View.GONE }
        root.addView(info)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = sizes[sizeIdx]; setTextColor(cFg); setTextIsSelectable(true)
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        hscroll = HorizontalScrollView(this).apply { isFillViewport = true; addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val vscroll = ScrollView(this).apply { isFillViewport = true; addView(hscroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        spin = ProgressBar(this)
        val frame = android.widget.FrameLayout(this)
        frame.addView(vscroll, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(spin, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setWrap(true)

        Thread({
            var text: String? = null
            var note: String? = null
            var err: String? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 30000
                if (c.responseCode != 200) throw java.io.IOException("HTTP " + c.responseCode)
                val buf = ByteArray(MAX + 1)
                var n = 0
                c.inputStream.use { ins ->
                    while (n < buf.size) {
                        val r = ins.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                val cut = n > MAX
                if (cut) { n = MAX; note = "Showing the first 1 MB only. Use \"Open with\u2026\" in the browser menu for the whole file." }
                text = decode(buf.copyOf(n))
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            val rText: String? = text
            val rNote: String? = note
            val rErr: String? = err
            ui.post {
                if (gone) return@post
                spin.visibility = android.view.View.GONE
                if (rErr != null) { body.text = "Cannot open this file:\n$rErr" }
                else {
                    body.text = if (rText.isNullOrEmpty()) "(empty file)" else rText
                    if (rNote != null) { info.text = rNote; info.visibility = android.view.View.VISIBLE }
                }
            }
        }, "text-load").also { it.isDaemon = true }.start()
    }

    private fun setWrap(on: Boolean) {
        wrap = on
        body.setHorizontallyScrolling(!on)
        hscroll.requestLayout()
    }

    /** UTF-8 (with or without BOM), UTF-16 with BOM, otherwise the Turkish Windows code page. */
    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())))
            return String(b, Charsets.UTF_16)
        val s = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, s, b.size - s)).toString()
        } catch (_: CharacterCodingException) {
            // a 1 MB cut may end inside a multi-byte character: retry without the last 3 bytes before falling back to the code page
            try {
                val e = maxOf(s, b.size - 3)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, s, e - s)).toString()
            } catch (_: CharacterCodingException) {
                String(b, s, b.size - s, Charset.forName("windows-1254"))
            }
        }
    }

    override fun onDestroy() { gone = true; ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
