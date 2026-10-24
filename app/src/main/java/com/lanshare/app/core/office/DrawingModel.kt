package com.lanshare.app.core.office

/**
 * Part 11: pictures (`xdr:pic`) and simple shapes / text boxes (`xdr:sp`) of a sheet's drawing part. Pure Kotlin (no android.*).
 * Anchors are the same [XAnchor]s the charts use. Pictures keep their bytes (png / jpeg / gif / bmp / webp / svg only, 3 MB each);
 * shapes keep only what the viewer draws: preset geometry name (ellipse / roundRect drawn rounded, everything else as a rectangle),
 * fill, outline, text (paragraphs joined by newlines), text colour and size.
 */
class XPic(val anchor: XAnchor, val mime: String, val bytes: ByteArray)

class XShape(
    val anchor: XAnchor, val prst: String, val fill: String?, val line: String?,
    val text: String, val textColor: String?, val sizePt: Double
)

class XDrawingExtras(val pics: List<XPic>, val shapes: List<XShape>)

object DrawingParser {
    private const val MAX_PICS = 30
    private const val MAX_SHAPES = 60
    private const val MAX_PIC_BYTES = 3 shl 20

    private fun mimeOf(target: String): String? = when (target.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> null   // emf / wmf / tiff and others cannot be shown in a WebView
    }

    /** Pictures and shapes of one drawing part. Never throws: a broken anchor is skipped. */
    fun parse(pkg: OoxmlPackage, drawingPart: String, theme: Theme): XDrawingExtras {
        val pics = ArrayList<XPic>(); val shapes = ArrayList<XShape>()
        try {
            val x = pkg.xml(drawingPart) ?: return XDrawingExtras(pics, shapes)
            val rels = pkg.rels(drawingPart)
            var anchors = 0
            while (anchors < 400 && (pics.size < MAX_PICS || shapes.size < MAX_SHAPES)) {
                val t = x.next(); if (t == MiniXml.T.EOF) break
                if (t != MiniXml.T.START) continue
                val kind = when (x.name) { "twoCellAnchor" -> 0; "oneCellAnchor" -> 1; "absoluteAnchor" -> 2; else -> -1 }
                if (kind < 0) continue
                anchors++
                val a = ChartParser.tree(x)
                try {
                    val anchor = ChartParser.anchorOf(a, kind) ?: continue
                    if (a.find("chart") != null) continue   // chart frames are the charts' business
                    val pic = a.find("pic")
                    if (pic != null) {
                        if (pics.size >= MAX_PICS) continue
                        val rid = pic.find("blip")?.emb ?: continue
                        val rel = rels[rid] ?: continue
                        if (rel.external) continue
                        val mime = mimeOf(rel.target) ?: continue
                        val bytes = pkg.read(rel.target) ?: continue
                        if (bytes.isEmpty() || bytes.size > MAX_PIC_BYTES) continue
                        pics.add(XPic(anchor, mime, bytes))
                        continue
                    }
                    if (shapes.size >= MAX_SHAPES) continue
                    val sp = a.find("sp") ?: continue
                    val spPr = sp.kid("spPr")
                    val prst = spPr?.kid("prstGeom")?.prst ?: "rect"
                    val style = sp.kid("style")
                    var fill: String? = null
                    if (spPr?.kid("noFill") == null) {
                        fill = spPr?.kid("solidFill")?.let { ChartParser.colorOf(it, theme) }
                        if (fill == null) fill = spPr?.kid("gradFill")?.find("gs")?.let { ChartParser.colorOf(it, theme) }
                        if (fill == null && spPr?.kid("solidFill") == null && spPr?.kid("gradFill") == null) fill = style?.kid("fillRef")?.takeIf { it.idx != "0" }?.let { ChartParser.colorOf(it, theme) }
                    }
                    val ln = spPr?.kid("ln")
                    var line: String? = null
                    if (ln?.kid("noFill") == null) {
                        line = ln?.kid("solidFill")?.let { ChartParser.colorOf(it, theme) }
                        if (line == null && ln?.kid("solidFill") == null) line = style?.kid("lnRef")?.takeIf { it.idx != "0" }?.let { ChartParser.colorOf(it, theme) }
                    }
                    val paras = ArrayList<String>()
                    for (p in sp.kid("txBody")?.all("p") ?: emptyList()) {
                        val runs = ArrayList<String>(); p.texts("t", runs)
                        paras.add(runs.joinToString(""))
                    }
                    val text = paras.joinToString("\n").trim()
                    if (text.isEmpty() && fill == null && line == null) continue   // nothing visible
                    val rPr = sp.find("rPr")
                    val tcol = rPr?.kid("solidFill")?.let { ChartParser.colorOf(it, theme) } ?: style?.kid("fontRef")?.let { ChartParser.colorOf(it, theme) }
                    val size = (rPr?.sz?.toDoubleOrNull() ?: 1100.0) / 100.0
                    shapes.add(XShape(anchor, prst, fill, line, text.take(2000), tcol, size.coerceIn(5.0, 96.0)))
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
        return XDrawingExtras(pics, shapes)
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64 without line breaks (java.util.Base64 needs API 26, the app has minSdk 24; this stays JVM-testable). */
    fun base64(b: ByteArray): String {
        val sb = StringBuilder((b.size + 2) / 3 * 4)
        var i = 0
        while (i < b.size) {
            val b0 = b[i].toInt() and 0xFF
            val b1 = if (i + 1 < b.size) b[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < b.size) b[i + 2].toInt() and 0xFF else 0
            sb.append(B64[b0 shr 2]).append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < b.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < b.size) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
