package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import com.lanshare.app.core.Core
import com.lanshare.app.core.uniqueName
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Basit resim düzenleyici: döndür (90°), kırp (sürükle), yeniden boyutlandır. ImageViewerActivity'den açılır.
 * Önizleme küçültülmüş bitmap üzerinde yapılır; kaydederken işlemler özgün dosyaya (tam çözünürlük) bir kerede uygulanır.
 * Kaydet: "Kopya olarak" (yanına "ad (edited).uzantı") veya "Aynı dosyanın üstüne yaz" (yalnızca bu telefondaki jpg/png/webp).
 */
class ImageEditActivity : Activity() {
    companion object {
        const val X_SRC = "src"          // görüntüleyicinin önbellekteki özgün dosyası
        const val X_DEV = "dev"
        const val X_PATH = "path"        // sanal yol (LocalFs)
        const val X_NAME = "name"
        const val R_REPLACED = "replaced"
        const val R_SIZE = "size"
        private const val PREVIEW = 2048
        private const val MAX_SIDE = 8192
        private const val MAX_PIX = 64_000_000L
        private const val ACCENT = 0xFFFF8F00.toInt()
    }

    private lateinit var src: File
    private var dev = "local"
    private var vpath = ""
    private var name = "image.jpg"
    private var probe: ImgEdit.Probe? = null
    private var base: Bitmap? = null          // EXIF uygulanmış önizleme (dönüş 0)
    private var view: Bitmap? = null          // base + rot
    private var rot = 0                       // çeyrek tur, saat yönü
    private var scale = 1f                    // çıktı boyutu / kırpılan boyut

    private lateinit var cropV: CropView
    private lateinit var subTv: TextView
    private lateinit var spin: ProgressBar
    private var saving = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(b: Context) = super.attachBaseContext(UiScale.wrap(b))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val s = intent.getStringExtra(X_SRC)
        if (s == null || !File(s).isFile) { finish(); return }
        src = File(s)
        dev = intent.getStringExtra(X_DEV) ?: "local"
        vpath = intent.getStringExtra(X_PATH) ?: ""
        name = intent.getStringExtra(X_NAME) ?: "image.jpg"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        Thread {
            try {
                val pr = ImgEdit.probe(src)
                val bm = ImgEdit.preview(src, pr, PREVIEW)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    probe = pr; base = bm; view = bm
                    spin.visibility = View.GONE
                    cropV.set(bm, RectF(0f, 0f, 1f, 1f))
                    refreshInfo()
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Cannot open this picture: " + (t.message ?: "error"), Toast.LENGTH_LONG).show(); finish() }
            }
        }.start()
    }

    // ---------------------------------------------------------------- arayüz (kodla)
    private fun buildUi() {
        fun btn(txt: String, sp: Float) = TextView(this).apply {
            text = txt; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(44)
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val close = btn("✕", 20f).apply { setOnClickListener { finish() } }
        val titleTv = TextView(this).apply { text = name; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subTv = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1 }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(subTv) }
        val save = btn("Save", 15f).apply {
            setTextColor(ACCENT); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { askSave() }
        }
        top.addView(close)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(save)

        val frame = android.widget.FrameLayout(this)
        cropV = CropView(this).apply { onChange = { refreshInfo() } }
        spin = ProgressBar(this).apply { layoutParams = android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER) }
        frame.addView(cropV, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(spin)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        fun tool(icon: String, label: String, act: () -> Unit) = btn("$icon\n$label", 12f).apply {
            setOnClickListener { if (base != null && !saving) act() }
        }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(tool("↺", "Rotate left") { rotate(false) }, lp())
        bar.addView(tool("↻", "Rotate right") { rotate(true) }, lp())
        bar.addView(tool("⤢", "Resize") { resizeDialog() }, lp())
        bar.addView(tool("⟲", "Reset") { resetAll() }, lp())

        root.addView(top)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        setContentView(root)
    }

    // ---------------------------------------------------------------- durum
    /** Döndürülmüş görüntünün (özgün çözünürlük) boyutu. */
    private fun rotDims(): Pair<Int, Int> {
        val p = probe ?: return 1 to 1
        return if (rot % 2 == 0) p.ow to p.oh else p.oh to p.ow
    }

    /** Kırpılan alanın özgün piksel boyutu. */
    private fun cropPx(): Pair<Int, Int> {
        val (rw, rh) = rotDims()
        val c = cropV.crop
        return max(1, ((c.right - c.left) * rw).roundToInt()) to max(1, ((c.bottom - c.top) * rh).roundToInt())
    }

    /** Çıktı boyutu: kırpılan boyut * scale, uzun kenar ve toplam piksel sınırlı. */
    private fun outDims(): Pair<Int, Int> {
        val (cw, ch) = cropPx()
        var w = cw * scale.toDouble()
        var h = ch * scale.toDouble()
        val k = minOf(1.0, MAX_SIDE / max(w, h), Math.sqrt(MAX_PIX / (w * h)))
        w *= k; h *= k
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    private fun changed(): Boolean {
        val c = cropV.crop
        return rot != 0 || scale != 1f || c.left > 0.0005f || c.top > 0.0005f || c.right < 0.9995f || c.bottom < 0.9995f
    }

    private fun refreshInfo() {
        val p = probe ?: return
        val (w, h) = outDims()
        subTv.text = if (changed()) "${p.ow}×${p.oh} → $w×$h" else "${p.ow}×${p.oh}"
    }

    private fun rotate(cw: Boolean) {
        val b = base ?: return
        rot = (rot + (if (cw) 1 else 3)) % 4
        val r = cropV.crop
        // kırpma dikdörtgenini de aynı yönde döndür
        val n = if (cw) RectF(1 - r.bottom, r.left, 1 - r.top, r.right) else RectF(r.top, 1 - r.right, r.bottom, 1 - r.left)
        val old = view
        val nv = if (rot == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot * 90f) }, true)
        view = nv
        cropV.set(nv, n)
        if (old != null && old !== b && old !== nv) old.recycle()
        refreshInfo()
    }

    private fun resetAll() {
        val b = base ?: return
        val old = view
        rot = 0; scale = 1f; view = b
        cropV.set(b, RectF(0f, 0f, 1f, 1f))
        if (old != null && old !== b) old.recycle()
        refreshInfo()
    }

    // ---------------------------------------------------------------- yeniden boyutlandır
    private fun resizeDialog() {
        val (cw, ch) = cropPx()
        val (ow, oh) = outDims()
        fun et(v: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(v.toString()); setSelectAllOnFocus(true); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val wEt = et(ow)
        val hEt = et(oh)
        var busy = false
        fun link(from: EditText, to: EditText, f: (Int) -> Int) = from.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (busy) return
                val v = s?.toString()?.toIntOrNull() ?: return
                busy = true; to.setText(max(1, f(v)).toString()); busy = false
            }
        })
        link(wEt, hEt) { w -> (w * ch.toDouble() / cw).roundToInt() }
        link(hEt, wEt) { h -> (h * cw.toDouble() / ch).roundToInt() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(wEt)
            addView(TextView(this@ImageEditActivity).apply { text = "  ×  "; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
            addView(hEt)
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (pc in intArrayOf(100, 75, 50, 25)) {
            chips.addView(TextView(this).apply {
                text = "$pc%"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(ACCENT); gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { wEt.setText(max(1, (cw * pc / 100.0).roundToInt()).toString()) }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0)
            addView(row); addView(chips)
        }
        AlertDialog.Builder(this)
            .setTitle("Resize (px)")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                val w = wEt.text.toString().toIntOrNull()
                if (w != null && w > 0) { scale = w.toFloat() / cw; refreshInfo() }
            }
            .show()
    }

    // ---------------------------------------------------------------- kaydet
    private fun extOf(n: String) = if (n.lastIndexOf('.') > 0) n.substring(n.lastIndexOf('.')).lowercase() else ""
    private fun canReplace() = dev == "local" && !vpath.contains('!') && vpath.isNotEmpty() && extOf(name) in setOf(".jpg", ".jpeg", ".png", ".webp")

    private fun askSave() {
        if (base == null || saving) return
        if (!changed()) { Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show(); return }
        if (canReplace()) {
            AlertDialog.Builder(this).setTitle("Save")
                .setItems(arrayOf("Save as copy", "Replace original")) { _, i -> doSave(i == 1) }
                .setNegativeButton("Cancel", null).show()
        } else doSave(false)
    }

    @Suppress("DEPRECATION")
    private fun doSave(replace: Boolean) {
        val pr = probe ?: return
        val c = RectF(cropV.crop)
        val r = rot
        val (w, h) = outDims()
        saving = true
        val dlg = AlertDialog.Builder(this).setMessage("Saving…").setCancelable(false).show()
        Thread {
            var err: String? = null
            var res: File? = null
            try {
                val ext0 = extOf(name)
                val ext = if (ext0 == ".jpeg") ".jpeg" else if (ext0 in setOf(".jpg", ".png", ".webp")) ext0 else ".jpg"
                val fmt = when (ext) {
                    ".png" -> Bitmap.CompressFormat.PNG
                    ".webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                val target: File
                if (replace) target = Core.local.real(vpath)
                else {
                    val dir = if (dev == "local" && !vpath.contains('!') && vpath.isNotEmpty()) Core.local.real(vpath).parentFile!!
                              else File(Core.local.root, "Pictures/LANShare Edited").apply { mkdirs() }
                    val stem = name.substringBeforeLast('.', name)
                    target = File(dir, uniqueName("$stem (edited)$ext", (dir.list() ?: emptyArray<String>()).toSet(), false))
                }
                val bm = ImgEdit.render(src, pr, r, c, w, h, fmt == Bitmap.CompressFormat.JPEG)
                val tmp = File(target.parentFile, target.name + ".lspart")
                try {
                    tmp.outputStream().use { o -> if (!bm.compress(fmt, if (fmt == Bitmap.CompressFormat.PNG) 100 else 95, o)) throw IOException("cannot encode the picture") }
                } finally { bm.recycle() }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }   // aynı klasörde rename; olmazsa kopyala
                res = target
            } catch (e: OutOfMemoryError) { err = "Not enough memory for this size"
            } catch (e: Exception) { err = e.message ?: "error" }
            val rf = res
            val re = err
            runOnUiThread {
                saving = false
                if (!isDestroyed) dlg.dismiss()
                if (rf != null) {
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(rf.path), null, null)
                    Toast.makeText(this, if (replace) "Saved" else "Saved: " + rf.name, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, Intent().putExtra(R_REPLACED, replace).putExtra(R_SIZE, rf.length()))
                    finish()
                } else Toast.makeText(this, "Cannot save: " + (re ?: "error"), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onDestroy() {
        cropV.set(null, RectF(0f, 0f, 1f, 1f))
        val v = view; val b = base
        if (v != null && v !== b) v.recycle()
        b?.recycle()
        view = null; base = null
        super.onDestroy()
    }
}

/** Çözme / önizleme / tam çözünürlükte uygulama yardımcıları. */
internal object ImgEdit {
    /** Özgün boyut + EXIF yönü; [ow]/[oh] yön uygulandıktan sonraki boyut. */
    class Probe(val w: Int, val h: Int, val ori: Int) {
        private val swap get() = ori == ExifInterface.ORIENTATION_TRANSPOSE || ori == ExifInterface.ORIENTATION_ROTATE_90 ||
            ori == ExifInterface.ORIENTATION_TRANSVERSE || ori == ExifInterface.ORIENTATION_ROTATE_270
        val ow get() = if (swap) h else w
        val oh get() = if (swap) w else h
    }

    fun probe(f: File): Probe {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw IOException("This device cannot decode this picture")
        val ori = try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        return Probe(o.outWidth, o.outHeight, ori)
    }

    /** EXIF yönünü düzelten matris (başlangıç noktası etrafında; çağıran taşımayı mapRect ile düzeltir). */
    fun orient(ori: Int): Matrix {
        val m = Matrix()
        when (ori) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> {}
        }
        return m
    }

    private fun decode(f: File, sample: Int): Bitmap {
        var s = sample
        while (true) {
            try {
                return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: throw IOException("This device cannot decode this picture")
            } catch (e: OutOfMemoryError) { if (s >= 64) throw e; s *= 2 }
        }
    }

    /** Uzun kenarı en az [maxLong] olan en küçük 2'nin katı örneklemeyle çözülmüş, EXIF yönü uygulanmış önizleme. */
    fun preview(f: File, p: Probe, maxLong: Int): Bitmap {
        var s = 1
        while (max(p.w, p.h) / (s * 2) >= maxLong) s *= 2
        val bm = decode(f, s)
        val m = orient(p.ori)
        if (m.isIdentity) return bm
        val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        if (r !== bm) bm.recycle()
        return r
    }

    /**
     * Tam çözünürlükte işlemi uygular: EXIF yönü -> [rot] çeyrek tur -> [crop] (0..1, döndürülmüş görüntüye göre) -> [outW]x[outH].
     * Kaynak yalnızca çıktıdan küçük düşmeyecek kadar örneklenir; tek geçişte (Canvas) çizilir.
     */
    fun render(f: File, p: Probe, rot: Int, crop: RectF, outW: Int, outH: Int, whiteBg: Boolean): Bitmap {
        val rw = if (rot % 2 == 0) p.ow else p.oh
        val rh = if (rot % 2 == 0) p.oh else p.ow
        val cw = max(1.0, (crop.right - crop.left) * rw.toDouble())
        val ch = max(1.0, (crop.bottom - crop.top) * rh.toDouble())
        var s = 1
        while (cw / (s * 2) >= outW && ch / (s * 2) >= outH) s *= 2
        val bm = decode(f, s)
        try {
            val m = orient(p.ori)
            m.postRotate(rot * 90f)
            val rect = RectF(0f, 0f, bm.width.toFloat(), bm.height.toFloat())
            m.mapRect(rect)
            m.postTranslate(-rect.left, -rect.top)          // artık (0,0)-(rect.width, rect.height)
            val sw = rect.width()
            val sh = rect.height()
            val cwS = max(1f, (crop.right - crop.left) * sw)
            val chS = max(1f, (crop.bottom - crop.top) * sh)
            m.postTranslate(-crop.left * sw, -crop.top * sh)
            m.postScale(outW / cwS, outH / chS)
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            if (whiteBg) cv.drawColor(Color.WHITE)
            cv.drawBitmap(bm, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            return out
        } finally { bm.recycle() }
    }
}

/** Resmi gösterir, üstüne sürüklenebilir kırpma dikdörtgeni çizer (köşe / kenar = boyutlandır, içi = taşı). [crop] 0..1 normalize. */
internal class CropView(c: Context) : View(c) {
    private var bmp: Bitmap? = null
    val crop = RectF(0f, 0f, 1f, 1f)
    private val ir = RectF()                      // resmin görünümdeki dikdörtgeni
    var onChange: (() -> Unit)? = null
    private val d = c.resources.displayMetrics.density
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDim = Paint().apply { color = 0x99000000.toInt() }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val pGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val pHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND }

    private val L = 1; private val T = 2; private val R = 4; private val B = 8; private val MOVE = 16
    private var mode = 0
    private var lx = 0f
    private var ly = 0f

    fun set(b: Bitmap?, r: RectF) { bmp = b; crop.set(r); layoutImg(); invalidate() }

    private fun layoutImg() {
        val b = bmp
        if (b == null || width == 0 || height == 0) { ir.setEmpty(); return }
        val pad = 20 * d
        val k = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * k
        val h = b.height * k
        ir.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); layoutImg() }

    private fun cr() = RectF(ir.left + crop.left * ir.width(), ir.top + crop.top * ir.height(), ir.left + crop.right * ir.width(), ir.top + crop.bottom * ir.height())

    override fun onDraw(cv: Canvas) {
        val b = bmp ?: return
        if (ir.isEmpty) return
        cv.drawBitmap(b, null, ir, pBmp)
        val c = cr()
        // kırpma dışını karart
        cv.drawRect(ir.left, ir.top, ir.right, c.top, pDim)
        cv.drawRect(ir.left, c.bottom, ir.right, ir.bottom, pDim)
        cv.drawRect(ir.left, c.top, c.left, c.bottom, pDim)
        cv.drawRect(c.right, c.top, ir.right, c.bottom, pDim)
        // üçlü ızgara + çerçeve
        for (i in 1..2) {
            cv.drawLine(c.left + c.width() * i / 3f, c.top, c.left + c.width() * i / 3f, c.bottom, pGrid)
            cv.drawLine(c.left, c.top + c.height() * i / 3f, c.right, c.top + c.height() * i / 3f, pGrid)
        }
        cv.drawRect(c, pLine)
        // köşe L'leri + kenar ortası çubukları
        val a = min(22 * d, min(c.width(), c.height()) / 3f)
        val e = pHandle
        cv.drawLine(c.left, c.top, c.left + a, c.top, e); cv.drawLine(c.left, c.top, c.left, c.top + a, e)
        cv.drawLine(c.right, c.top, c.right - a, c.top, e); cv.drawLine(c.right, c.top, c.right, c.top + a, e)
        cv.drawLine(c.left, c.bottom, c.left + a, c.bottom, e); cv.drawLine(c.left, c.bottom, c.left, c.bottom - a, e)
        cv.drawLine(c.right, c.bottom, c.right - a, c.bottom, e); cv.drawLine(c.right, c.bottom, c.right, c.bottom - a, e)
        val mx = (c.left + c.right) / 2f
        val my = (c.top + c.bottom) / 2f
        val h = min(14 * d, min(c.width(), c.height()) / 6f)
        cv.drawLine(mx - h, c.top, mx + h, c.top, e); cv.drawLine(mx - h, c.bottom, mx + h, c.bottom, e)
        cv.drawLine(c.left, my - h, c.left, my + h, e); cv.drawLine(c.right, my - h, c.right, my + h, e)
    }

    private fun hit(x: Float, y: Float): Int {
        val c = cr()
        val t = 28 * d
        val inX = x >= c.left - t && x <= c.right + t
        val inY = y >= c.top - t && y <= c.bottom + t
        var m = 0
        if (inY) {
            val dl = abs(x - c.left); val dr = abs(x - c.right)
            if (dl <= t && dl <= dr) m = m or L else if (dr <= t) m = m or R
        }
        if (inX) {
            val dt = abs(y - c.top); val db = abs(y - c.bottom)
            if (dt <= t && dt <= db) m = m or T else if (db <= t) m = m or B
        }
        if (m == 0 && c.contains(x, y)) m = MOVE
        return m
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null || ir.isEmpty) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { mode = hit(e.x, e.y); lx = e.x; ly = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (mode != 0) {
                val dx = (e.x - lx) / ir.width()
                val dy = (e.y - ly) / ir.height()
                lx = e.x; ly = e.y
                val mw = min(0.5f, 48 * d / ir.width())      // en az ~48dp
                val mh = min(0.5f, 48 * d / ir.height())
                if (mode == MOVE) {
                    val w = crop.width(); val h = crop.height()
                    val l = (crop.left + dx).coerceIn(0f, 1f - w)
                    val t = (crop.top + dy).coerceIn(0f, 1f - h)
                    crop.set(l, t, l + w, t + h)
                } else {
                    // sınırlar boş aralık vermesin diye max/min ile korunur (döndürme sonrası kırpma min boyuttan dar olabilir)
                    if (mode and L != 0) crop.left = (crop.left + dx).coerceIn(0f, max(0f, crop.right - mw))
                    if (mode and R != 0) crop.right = (crop.right + dx).coerceIn(min(1f, crop.left + mw), 1f)
                    if (mode and T != 0) crop.top = (crop.top + dy).coerceIn(0f, max(0f, crop.bottom - mh))
                    if (mode and B != 0) crop.bottom = (crop.bottom + dy).coerceIn(min(1f, crop.top + mh), 1f)
                }
                invalidate(); onChange?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }
}
