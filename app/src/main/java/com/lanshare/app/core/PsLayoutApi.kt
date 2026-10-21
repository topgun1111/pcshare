package com.lanshare.app.core

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Print space part 5: POST /api/pslayout. Android side of [PsLayoutJson]: reads the pictures of the print space,
 * finds how big each LOOKS (header decode only, EXIF turn applied; cached per picture id because the copy never changes),
 * and hands everything to the pure layout code. Wire format: see [PsLayoutJson].
 * NOT compiled on a device / NOT device-tested.
 */
object PsLayoutApi {
    private val dims = ConcurrentHashMap<String, IntArray>()

    private fun read(v: String): IntArray {
        val f = Core.local.real(v)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { BitmapFactory.decodeFile(f.path, o) } catch (_: Exception) {}
        var w = o.outWidth
        var h = o.outHeight
        if (w <= 0 || h <= 0) return intArrayOf(4000, 3000)   // header not readable (e.g. HEIC on an old phone): assume a 4:3 photo
        val ori = try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        if (ori == ExifInterface.ORIENTATION_ROTATE_90 || ori == ExifInterface.ORIENTATION_ROTATE_270 ||
            ori == ExifInterface.ORIENTATION_TRANSPOSE || ori == ExifInterface.ORIENTATION_TRANSVERSE) { val t = w; w = h; h = t }
        return intArrayOf(w, h)
    }

    private fun pics(): List<PsLayoutJson.Pict> = PrintSpace.items().map { it0 ->
        val d = dims.getOrPut(it0.id) { read(it0.path()) }
        PsLayoutJson.Pict(it0.id, d[0], d[1], it0.rot, it0.copies)
    }

    fun post(b: JSONObject): JSONObject {
        val list = pics()
        dims.keys.retainAll(list.map { p -> p.id }.toSet())   // forget removed pictures
        try { return PsLayoutJson.handle(b, list) }
        catch (e: IllegalArgumentException) { throw BadReq(e.message ?: "bad layout request") }
    }

    /** Part 6: the request's spec + plan composed into pages (what the editor shows), for the renderer. Same sizes / same errors as [post]. */
    fun resolve(b: JSONObject): PsLayoutJson.Resolved {
        val list = pics()
        dims.keys.retainAll(list.map { p -> p.id }.toSet())
        try { return PsLayoutJson.resolve(b, list) }
        catch (e: IllegalArgumentException) { throw BadReq(e.message ?: "bad layout request") }
    }
}
