package com.lanshare.app.pe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.lanshare.app.MainActivity
import com.lanshare.app.BuildConfig
import com.lanshare.app.NoteTint
import com.lanshare.app.R

/** Foreground service of "Phone Explorer": FTP server on the phone storage (Windows Explorer opens it natively) + UDP discovery for the PC tool. */
class PeService : Service() {
    private var ftp: FtpServer? = null
    private var discovery: PeDiscovery? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastBytes = 0L
    private var lastTick = 0L
    private var lastText = ""
    @Volatile private var binding = false
    @Volatile private var tlsCtx: javax.net.ssl.SSLContext? = null   // set at start when FTPS is switched on

    /** Notification refresh once a second: live speed + current file while files move. */
    private val tick = object : Runnable {
        override fun run() {
            val s = ftp ?: return
            val now = System.currentTimeMillis()
            val total = s.bytesTotal
            val speed = (total - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1)
            lastBytes = total; lastTick = now
            val text = if (s.activeTransfers > 0 || speed > 0)
                "${s.lastTransfer}  -  ${fmtSpeed(speed)}" + (if (s.activeTransfers > 1) "  (+${s.activeTransfers - 1} more)" else "")
            else idleText()
            if (text != lastText) { lastText = text; notify(text, s.activeTransfers > 0) }
            handler.postDelayed(this, 1000)
        }
    }

    private fun fmtSpeed(b: Long): String = when {
        b >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB/s"
        else -> "$b B/s"
    }

    private fun idleText() = "ftp://${PePrefs.localIp() ?: "?"}:${PePrefs.port(this)}" + (if (tlsCtx != null) "  (FTPS)" else "") + (if (PePrefs.readOnly(this)) "  (read-only)" else "")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer(); stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (ftp?.isRunning == true || binding) { startFg(); return }   // repeated start command: keep the running / starting server
        binding = true
        lastError = null
        startFg()
        val port = PePrefs.port(this)
        val srv = FtpServer(
            root = Environment.getExternalStorageDirectory(), port = port, user = PePrefs.USER,
            passwordProvider = { PePrefs.pin(this) },
            readOnly = { PePrefs.readOnly(this) },
            allowedDirs = { PePrefs.whitelist(this) },
            tls = { tlsCtx }, tlsRequired = { tlsCtx != null && PePrefs.tlsRequired(this) })
        // bind off the main thread; the UI sees the state through isRunning (polled)
        Thread {
            try {
                tlsCtx = if (PePrefs.tls(this)) try { PeTls.context(filesDir) } catch (_: Throwable) { null } else null   // certificate problem: plain FTP only
                srv.start()
                handler.post {
                    binding = false
                    ftp = srv
                    discovery = PeDiscovery(Build.MODEL ?: "Android", port, PePrefs.USER).also { it.start() }
                    acquireLocks()
                    instance = this
                    lastBytes = srv.bytesTotal; lastTick = System.currentTimeMillis(); lastText = ""
                    handler.postDelayed(tick, 1000)
                }
            } catch (e: Exception) {
                binding = false
                lastError = if (e is java.net.BindException) "Port $port is already in use" else (e.message ?: e.toString())
                handler.post { stopServer(); stopForeground(true); stopSelf() }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun stopServer() {
        binding = false
        handler.removeCallbacks(tick)
        ftp?.stop(); ftp = null
        discovery?.stop(); discovery = null
        releaseLocks()
        instance = null
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LANShare:pe").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LANShare:pe-wifi").apply { setReferenceCounted(false); acquire() }
        multicast = wm.createMulticastLock("LANShare:pe-mc").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { multicast?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wake = null; wifiLock = null; multicast = null
    }

    private fun build(text: String, transferring: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, PeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle(if (transferring) "Phone Explorer - transferring" else "Phone Explorer is sharing storage")
            .setContentText(text).setSmallIcon(R.drawable.ic_notification).setColor(NoteTint.lite(this)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun notify(text: String, transferring: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(text, transferring))
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Phone Explorer", NotificationManager.IMPORTANCE_LOW))
        val n = build(idleText(), false)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { android.util.Log.w("LANShare", "pe startForeground refused: $e") }
    }

    override fun onDestroy() { stopServer(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.lanshare.app.pe.STOP"
        private const val CHANNEL = "pe"
        private const val NOTIF_ID = 21

        @Volatile var instance: PeService? = null
        @Volatile var lastError: String? = null

        val isRunning: Boolean get() = instance?.ftp?.isRunning == true
        val clients: Int get() = instance?.ftp?.clients ?: 0

        fun start(c: Context) {
            lastError = null
            val i = Intent(c, PeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) { c.startService(Intent(c, PeService::class.java).setAction(ACTION_STOP)) }
    }
}

/** Starts sharing after boot / app update when "Start automatically" is on (needs all-files access already granted). */
class PeBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!PePrefs.autoStart(context)) return
        val ok = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) return
        try { PeService.start(context) } catch (_: Exception) {}
    }
}
