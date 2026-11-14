package com.lanshare.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Image viewer: the details sheet that slides up when the picture is pulled up (or from the menu's Details). Dark panel over a dimmed scrim, 62 % of
 * the screen high, scrolls. The Location section ends with Open in Google Maps / Copy / Share. The owner (ImageViewerActivity) drives it:
 * begin -> drag(total px) while the finger pulls -> settle(open). Dragging the header down, tapping the dim area or Back closes it.
 * NOT compiled in the Lumen environment (owner compile).
 */
internal class ImageInfoSheet(c: Context) : FrameLayout(c) {
    /** 0..1 while opening / closing: the viewer moves and shrinks the picture with it. */
    var onProgress: ((Float) -> Unit)? = null
    var onClosed: (() -> Unit)? = null
    /** Page this sheet belongs to, -1 while closed. */
    var pos = -1
    var frac = 0f; private set
    private var ph = 0
    private var anim: ValueAnimator? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val acc = 0xFF8AB4F8.toInt()
    private val fg = 0xFFE8EAED.toInt()
    private val mut = 0xFF9AA0A6.toInt()

    private val scrim = View(c).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
    private val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(2), dp(16), dp(48)) }
    private val panel = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    private fun tv(t: String, sp: Float, col: Int): TextView = TextView(context).apply { text = t; setTextColor(col); LumenTypography.fit(this, sp) }

    init {
        val r = dp(24).toFloat()
        panel.background = GradientDrawable().apply { setColor(0xFF202124.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        panel.elevation = dp(8).toFloat()
        val grab = View(context).apply {
            background = GradientDrawable().apply { setColor(0x66FFFFFF); cornerRadius = dp(2).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(6) }
        }
        val title = tv("Details", 20f, fg).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val close = tv("\u2715", 16f, fg).apply { gravity = Gravity.CENTER; minWidth = dp(48); minimumHeight = dp(48); setOnClickListener { settle(false) } }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(4), 0)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(grab); addView(bar) }
        head.setOnTouchListener(object : View.OnTouchListener {
            var y0 = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y0 = e.rawY; moved = false; anim?.cancel() }
                    MotionEvent.ACTION_MOVE -> { val d = e.rawY - y0; if (d > 0 && ph > 0) { moved = true; setFrac(1f - d / ph) } }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> settle(!(moved && frac < 0.8f))
                }
                return true
            }
        })
        val sv = ScrollView(context).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
        panel.addView(head)
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(scrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        ph = (h * 0.62f).toInt()
        (panel.layoutParams as LayoutParams).height = ph
        panel.requestLayout()
        setFrac(frac)
    }

    private fun setFrac(f: Float) {
        frac = f.coerceIn(0f, 1f)
        panel.translationY = (1f - frac) * ph
        scrim.alpha = 0.55f * frac
        onProgress?.invoke(frac)
    }

    /** A pull started on page [p]: the sheet becomes touchable and follows the finger. */
    fun begin(p: Int) { anim?.cancel(); pos = p; visibility = View.VISIBLE }

    /** [total] = how far the finger has moved up, in px. */
    fun drag(total: Float) { if (ph > 0) setFrac(total / (ph * 0.7f)) }

    fun isOpen() = visibility == View.VISIBLE && frac > 0.5f

    fun settle(open: Boolean) {
        anim?.cancel()
        val an = ValueAnimator.ofFloat(frac, if (open) 1f else 0f)
        anim = an
        var dead = false
        an.addUpdateListener { v -> setFrac(v.animatedValue as Float) }
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(x: Animator) { dead = true }
            override fun onAnimationEnd(x: Animator) { if (!dead && !open) { visibility = View.GONE; pos = -1; onClosed?.invoke() } }
        })
        an.duration = 220
        an.start()
    }

    /** Taps on the dim area above the panel close it; everything else is swallowed so the picture underneath is not touched. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        if (e.actionMasked == MotionEvent.ACTION_UP && e.y < height - ph + panel.translationY) settle(false)
        return true
    }

    // ------------------------------------------------------------------ content
    fun setLoading() { body.removeAllViews(); body.addView(note("Reading details\u2026")) }
    fun setError() { body.removeAllViews(); body.addView(note("Could not read the details of this picture.")) }

    fun setContent(secs: List<ImageInfo.Sec>, lat: Double?, lon: Double?, label: String) {
        body.removeAllViews()
        if (secs.isEmpty()) body.addView(note("No details found in this picture."))
        for (s in secs) {
            body.addView(tv(s.title, 14f, acc).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(16), 0, dp(4)) })
            for ((k, v) in s.rows) body.addView(row(k, v))
            if (s.title == "Location" && lat != null && lon != null) body.addView(mapButtons(lat, lon, label))
        }
    }

    private fun note(t: String) = tv(t, 14f, mut).apply { setPadding(0, dp(16), 0, dp(16)) }

    private fun row(k: String, v: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(tv(k, 12f, mut), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f))
        addView(tv(v, 14f, fg).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
    }

    private fun btn(t: String, filled: Boolean, act: () -> Unit): TextView = tv(t, 14f, if (filled) 0xFF0B1B3A.toInt() else acc).apply {
        gravity = Gravity.CENTER; minimumHeight = dp(48); setPadding(dp(16), dp(8), dp(16), dp(8)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); if (filled) setColor(acc) else { setColor(0x00000000); setStroke(dp(1), 0x668AB4F8) } }
        setOnClickListener { act() }
    }

    private fun mapButtons(lat: Double, lon: Double, label: String): View {
        val ll = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val url = "https://www.google.com/maps/search/?api=1&query=$ll"
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4)) }
        col.addView(btn("Open in Google Maps", true) { openMaps(ll, url, label) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val two = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        two.addView(btn("Copy coordinates", false) { copy(ll) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        two.addView(btn("Share link", false) { share(url) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        col.addView(two)
        return col
    }

    /** Google Maps app first (geo: link with a pin named after the file), the maps web page in the browser when the app is not installed. */
    private fun openMaps(ll: String, url: String, label: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$ll(" + Uri.encode(label) + ")")).setPackage("com.google.android.apps.maps")
            context.startActivity(i)
        } catch (_: Exception) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (_: Exception) { Lm.snack(context, "No app to open the map", Toast.LENGTH_SHORT) }
        }
    }

    private fun copy(text: String) {
        try {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Coordinates", text))
            Lm.snack(context, "Coordinates copied", Toast.LENGTH_SHORT)
        } catch (_: Exception) { }
    }

    private fun share(url: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            context.startActivity(Intent.createChooser(i, "Share location"))
        } catch (_: Exception) { }
    }
}
