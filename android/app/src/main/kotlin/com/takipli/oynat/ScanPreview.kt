package com.takipli.oynat

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.takipli.oynat.core.COCO_SKELETON
import com.takipli.oynat.core.PreviewOverlay
import kotlin.math.max

/** Part 12: one preview frame from the running scan: small bitmap (longer side <= [PREVIEW_SIDE]) + what to draw on it. */
class ScanPreview(val t: Double, val bitmap: Bitmap, val overlay: PreviewOverlay)

const val PREVIEW_SIDE = 320
const val PREVIEW_MIN_MS = 200L // at most ~5 previews per second

/** Downscaled copy that is safe to keep after the scanner recycles `src`. */
fun previewBitmap(src: Bitmap): Bitmap {
    val r = PREVIEW_SIDE.toFloat() / max(src.width, src.height)
    if (r >= 1f) return src.copy(Bitmap.Config.ARGB_8888, false)
    return Bitmap.createScaledBitmap(src, max(1, (src.width * r).toInt()), max(1, (src.height * r).toInt()), true)
}

private const val KP_MIN = 0.35 // = Config.kpMinConf (joints below it are not drawn)

/** Frame + skeletons + target person (yellow) + part box (red) + camera crop (green). */
@Composable
fun ScanPreviewView(p: ScanPreview) {
    Box(Modifier.fillMaxWidth().aspectRatio(p.bitmap.width / p.bitmap.height.toFloat())) {
        Image(p.bitmap.asImageBitmap(), null, Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            fun at(x: Double, y: Double) = Offset((x * size.width).toFloat(), (y * size.height).toFloat())
            fun rect(b: DoubleArray, c: Color, w: Float) =
                drawRect(c, at(b[0], b[1]), Size(((b[2] - b[0]) * size.width).toFloat(), ((b[3] - b[1]) * size.height).toFloat()), style = Stroke(w))
            for (o in p.overlay.persons) {
                val c = if (o.isTarget) Color.Yellow else Color.White
                rect(o.box, c.copy(alpha = if (o.isTarget) 1f else 0.5f), 2f)
                val k = o.kp ?: continue
                for ((a, b) in COCO_SKELETON) if (k[3 * a + 2] >= KP_MIN && k[3 * b + 2] >= KP_MIN) {
                    drawLine(c, at(k[3 * a], k[3 * a + 1]), at(k[3 * b], k[3 * b + 1]), 3f)
                }
            }
            p.overlay.partBox?.let { rect(it, Color.Red, 4f) }
            p.overlay.crop?.let { rect(it, Color.Green, 3f) }
        }
    }
}
