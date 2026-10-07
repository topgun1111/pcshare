package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * On-device picture understanding (Google ML Kit, models bundled in the APK: no network, no API key).
 *  - [labelsOf]: what is in a picture ("dog", "car", "food"...). Cached on disk, so a folder is only analysed once.
 *    Used by LocalFs.search: typing "dog" in a folder also finds pictures that show a dog.
 *  - [ocr]: text in one picture (Latin script, covers Turkish and English). Used by the image viewer.
 * Never call [labelsOf] on the UI thread (it blocks on the ML Kit task).
 */
object ImgAi {
    private val IMG = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val LABEL_PX = 640

    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun isImg(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMG

    /** Pictures worth analysing: not huge, and not inside cache-like folders (".thumbnails", "thumbnails"). */
    fun worth(f: File): Boolean {
        if (!isImg(f.name) || f.length() !in 1..MAX_BYTES) return false
        val d = f.parentFile?.name ?: return true
        return !d.startsWith(".") && !d.equals("thumbnails", true)
    }

    // ---------------------------------------------------------------- label cache (tab-separated lines: key \t labels; later lines win)
    private val map = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false
    private fun store(): File? = Core.appCtx?.let { File(it.filesDir, "imglabels.tsv") }
    private fun key(f: File) = f.path + "|" + f.length() + "|" + f.lastModified()

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try {
            store()?.takeIf { it.isFile }?.forEachLine { l ->
                val t = l.indexOf('\t')
                if (t > 0) map[l.substring(0, t)] = l.substring(t + 1)
            }
        } catch (_: Exception) { }
    }

    @Synchronized private fun put(k: String, v: String) {
        map[k] = v
        try {
            val s = store() ?: return
            if (s.length() > 4L * 1024 * 1024) {   // compact: one line per picture
                s.writeText(map.entries.joinToString("") { it.key + "\t" + it.value + "\n" })
            } else s.appendText(k + "\t" + v + "\n")
        } catch (_: Exception) { }
    }

    /** Cached labels (comma-separated, lowercase) or null when the picture has not been analysed yet. */
    fun cached(f: File): String? { load(); return map[key(f)] }

    /** Labels of [f]; analyses it when [compute] and it is not cached yet. null = unknown (not analysed / model failed). */
    fun labelsOf(f: File, compute: Boolean = true): String? {
        load()
        val k = key(f)
        map[k]?.let { return it }
        if (!compute) return null
        val bmp = decode(f) ?: run { put(k, ""); return "" }   // undecodable: remember, so it is not retried on every search
        return try {
            val res = Tasks.await(labeler.process(InputImage.fromBitmap(bmp, rotation(f))), 20, TimeUnit.SECONDS)
            res.joinToString(",") { it.text.lowercase() }.also { put(k, it) }
        } catch (_: Exception) { null } finally { bmp.recycle() }
    }

    private fun rotation(f: File): Int = try {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) { 0 }

    private fun decode(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            var s = 1
            while (o.outWidth / (s * 2) >= LABEL_PX && o.outHeight / (s * 2) >= LABEL_PX) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- background indexing (pictures the search had no time for)
    private val bg = Executors.newSingleThreadExecutor { r -> Thread(r, "imgai").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }
    private val queued = ConcurrentHashMap.newKeySet<String>()

    /** Analyses [files] one after another in the background, so the next search of that folder finds them all. */
    fun indexAsync(files: List<File>) {
        for (f in files.take(3000)) {
            val k = key(f)
            if (!queued.add(k)) continue
            bg.execute { try { labelsOf(f, true) } finally { queued.remove(k) } }
        }
    }

    // ---------------------------------------------------------------- matching ("köpek" finds "dog")
    private val TR = mapOf(
        "köpek" to "dog", "kedi" to "cat", "kuş" to "bird", "balık" to "fish", "inek" to "cow", "tavşan" to "rabbit",
        "araba" to "car", "otomobil" to "car", "bisiklet" to "bicycle", "motosiklet" to "motorcycle", "uçak" to "airplane", "tekne" to "boat", "gemi" to "ship", "otobüs" to "bus", "tren" to "train",
        "çiçek" to "flower", "ağaç" to "tree", "bitki" to "plant", "çim" to "grass", "orman" to "forest", "dağ" to "mountain", "deniz" to "sea", "plaj" to "beach", "göl" to "lake", "nehir" to "river",
        "gökyüzü" to "sky", "bulut" to "cloud", "kar" to "snow", "gün batımı" to "sunset", "gece" to "night",
        "insan" to "person", "kişi" to "person", "bebek" to "baby", "çocuk" to "child", "yüz" to "face", "gülümseme" to "smile",
        "yemek" to "food", "pizza" to "pizza", "pasta" to "cake", "kahve" to "coffee", "meyve" to "fruit", "sebze" to "vegetable",
        "bina" to "building", "köprü" to "bridge", "kitap" to "book", "telefon" to "phone", "bilgisayar" to "computer", "masa" to "table", "sandalye" to "chair",
    )

    /** Search terms for [q]: the word itself plus English labels it stands for (Turkish prefix match, so "köpekler" works). */
    fun terms(q: String): List<String> {
        val ql = q.trim().lowercase()
        val out = arrayListOf(ql)
        for ((tr, en) in TR) if (ql == tr || (tr.length >= 3 && ql.startsWith(tr))) out.add(en)
        return out
    }

    /** The first label of [labels] that matches one of [terms], or null. */
    fun match(labels: String?, terms: List<String>): String? {
        if (labels.isNullOrEmpty()) return null
        return labels.split(',').firstOrNull { l -> terms.any { l.contains(it) } }
    }

    // ---------------------------------------------------------------- OCR
    /** Text in [bmp] (already upright). [cb] gets (text, null) or (null, error message); it runs on the main thread. */
    fun ocr(bmp: Bitmap, cb: (String?, String?) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cb(it.text, null) }
                .addOnFailureListener { cb(null, it.message ?: "recognition failed") }
        } catch (e: Throwable) { cb(null, e.message ?: "recognition failed") }
    }
}
