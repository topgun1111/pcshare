package com.lanshare.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.lanshare.app.core.Cfg
import com.lanshare.app.core.Core
import com.lanshare.app.core.Notes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clipboard text / links pushed by another device (/p/clip).
 * App on screen: a box in the bottom-left corner (tap = open link in the default browser, or copy the text); a link is also opened at once.
 * App in the background: a heads-up notification (tap = open link in the default browser, or copy the text).
 */
object ClipPush {
    private const val CH = "lanshare_clip"
    private const val ACT_COPY = "com.lanshare.app.CLIP_COPY"
    private val ui = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(100)
    private val texts = ConcurrentHashMap<Int, String>()   // notification id -> text (Intent extras are size limited)
    @Volatile var front: Activity? = null                  // MainActivity while it is resumed
    private var box: View? = null
    private var hide: Runnable? = null

    /** The whole text is one web address -> that address (with scheme), else null. */
    fun linkOf(t: String): String? {
        val s = t.trim()
        if (s.isEmpty() || s.length > 4096 || s.any { it.isWhitespace() }) return null
        val low = s.lowercase()
        return when {
            low.startsWith("http://") || low.startsWith("https://") -> s
            low.startsWith("www.") && s.indexOf('.', 4) > 4 -> "https://$s"
            else -> null
        }
    }

    /** Opens [link] in the default browser (no chooser). */
    fun open(ctx: Context, link: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }

    fun copy(ctx: Context, text: String) {
        try { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LANShare", text)) } catch (_: Exception) {}
    }

    /** Called on a server thread when another device sent [text]. [quiet] = a clipboard-sync push: honoured only when this phone's "Auto-copy" is on, else it is shown like any other text. */
    fun received(text: String, from: String, quiet: Boolean = false) {
        val ctx = Core.appCtx ?: return
        val link = linkOf(text)
        Notes.add(false, from, text)   // text inbox (history + the inbox screen if it is open)
        val auto = Cfg.autoCopy()
        if (auto) { ClipSync.mark(text); copy(ctx, text) }   // writing the clipboard is allowed in the background; marked so clipboard sync does not echo it back
        if (quiet && auto) return                            // silent: no box, no notification, no browser
        ui.post {
            val a = front
            if (a != null) try { show(a, text, from, link) } catch (e: Throwable) { android.util.Log.w("LANShare", "clip box failed: $e") }
            if (!Notes.visible) notify(ctx, text, from, link)   // also covers a box that is hidden / missed, and keeps the item in the shade (not needed while the inbox is on screen)
            if (a != null && link != null) open(a, link)
        }
    }

    private fun dp(a: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.resources.displayMetrics).toInt()

    private fun navBar(a: Activity): Int {
        val r = a.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (r > 0) a.resources.getDimensionPixelSize(r) else 0
    }

    private fun dismiss() {
        hide?.let { ui.removeCallbacks(it) }; hide = null
        box?.let { (it.parent as? ViewGroup)?.removeView(it) }; box = null
    }

    private fun show(a: Activity, text: String, from: String, link: String?) {
        dismiss()
        val host = a.window.decorView as? ViewGroup ?: return   // top-most layer: nothing the app draws can cover the box
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 14f), dp(a, 10f), dp(a, 14f), dp(a, 12f))
            background = GradientDrawable().apply { setColor(0xF2202124.toInt()); cornerRadius = dp(a, 12f).toFloat() }
            elevation = dp(a, 16f).toFloat()
            isClickable = true
        }
        card.addView(TextView(a).apply {
            this.text = (if (link != null) "Link from " else "Clipboard from ") + from
            setTextColor(0xFFB8BCC0.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        card.addView(TextView(a).apply {
            this.text = text.trim()
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(a, 3f), 0, dp(a, 3f))
        })
        card.addView(TextView(a).apply {
            this.text = if (link != null) "Tap to open" else "Tap to copy"
            setTextColor(0xFFFFB77C.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        card.setOnClickListener {
            if (link != null) open(a, link) else { copy(a, text); Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show() }
            dismiss()
        }
        val maxW = minOf(a.resources.displayMetrics.widthPixels - dp(a, 32f), dp(a, 340f))
        host.addView(card, FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin = dp(a, 16f); bottomMargin = dp(a, 24f) + navBar(a) })
        card.bringToFront()
        box = card
        hide = Runnable { dismiss() }.also { ui.postDelayed(it, 10_000) }
    }

    private fun notify(ctx: Context, text: String, from: String, link: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Received clipboard", NotificationManager.IMPORTANCE_HIGH))
        val id = seq.incrementAndGet()
        texts[id] = text
        if (texts.size > 20) texts.keys.minOrNull()?.let { texts.remove(it) }
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val copyPi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, ClipCopyReceiver::class.java).setAction(ACT_COPY).putExtra("id", id), fl)
        val tapPi = if (link != null)
            PendingIntent.getActivity(ctx, id, Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE), fl)
        else copyPi
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle((if (link != null) "Link from " else "Clipboard from ") + from)
            .setContentText(text.trim().take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.trim().take(2000)))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setContentIntent(tapPi)
            .addAction(R.drawable.ic_notification, if (link != null) "Open" else "Copy", tapPi)
        if (link != null) b.addAction(R.drawable.ic_notification, "Copy", copyPi)
        try { nm.notify(id, b.build()) } catch (e: Throwable) { android.util.Log.w("LANShare", "clip notify failed: $e") }
    }

    fun textFor(id: Int): String? = texts[id]
    fun drop(id: Int) { texts.remove(id) }
}

/** "Copy" on a received-clipboard notification. */
class ClipCopyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        val t = ClipPush.textFor(id) ?: return
        ClipPush.copy(c, t)
        c.getSystemService(NotificationManager::class.java)?.cancel(id)
        ClipPush.drop(id)
        Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show()
    }
}
