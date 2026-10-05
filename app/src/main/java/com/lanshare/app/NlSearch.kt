package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Native twin of the page's #srow (back button, text box, clear button; 48 dp, padding 0/4, 40 dp buttons, 16 px text).
 * The HTML row stays underneath unchanged; this view is laid exactly on top of it (rect from ui.html nlSrPush()).
 * Typing never touches the WebView: text goes to NativeList (instant filter) and to the page (nlSr('q')).
 * Kill switch: NativeList.SEARCH=false or localStorage ls_nlq='0' -> the HTML input is used as before.
 */
class NlSearchRow(c: Context, private val d: Float) : LinearLayout(c) {
    var onText: ((String) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null

    private val et = EditText(c)
    private val back = FrameLayout(c)
    private val clr = FrameLayout(c)
    private val line = Paint()
    private var silent = false
    private var pal: NlPal? = null
    private fun dp(v: Float) = Math.round(v * d)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4f), 0, dp(4f), 0)
        setWillNotDraw(false)
        et.apply {
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = "Search in this folder"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(2f), 0, dp(2f), 0)
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: Editable?) { if (!silent) onText?.invoke(s?.toString() ?: "") }
            })
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
        }
        addView(back, LayoutParams(dp(40f), dp(40f)))
        addView(et, LayoutParams(0, dp(40f), 1f))
        addView(clr, LayoutParams(dp(40f), dp(40f)))
        back.setOnClickListener { onBack?.invoke() }
        clr.setOnClickListener { silent = true; et.setText(""); silent = false; onClear?.invoke(); focusInput() }
        back.contentDescription = "Back"; clr.contentDescription = "Clear"
    }

    fun setPal(p: NlPal) {
        pal = p
        setBackgroundColor(p.card)
        et.setTextColor(p.fg); et.setHintTextColor(p.mut)
        line.color = p.bd
        back.removeAllViews(); clr.removeAllViews()
        back.addView(NlGlyph(context, NlIcons.path("back"), p.fg, dp(24f)), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        clr.addView(NlGlyph(context, NlIcons.path("close"), p.fg, dp(24f)), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        invalidate()
    }

    /** Set the box text without telling anyone (page-side change, or reset on close). */
    fun setText(t: String) {
        if (et.text.toString() == t) return
        silent = true; et.setText(t); et.setSelection(t.length); silent = false
    }

    fun focusInput() {
        val show = Runnable {
            et.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
        post(show); postDelayed(show, 150)      // the 2nd try covers a view that was not laid out yet
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(et.windowToken, 0)
        et.clearFocus()
    }

    override fun dispatchDraw(cv: Canvas) {
        super.dispatchDraw(cv)
        cv.drawRect(0f, height - d, width.toFloat(), height.toFloat(), line)      // 1 css px bottom border
    }
}
