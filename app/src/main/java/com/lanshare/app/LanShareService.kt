package com.lanshare.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lanshare.app.core.Core

class LanShareService : Service() {
    private var started = false
    private var lastScan = 0L
    private val netCb = object : android.net.ConnectivityManager.NetworkCallback() {
        private fun go() {   // debounce: network events come in bursts
            val now = System.currentTimeMillis()
            if (now - lastScan < 3000) return
            lastScan = now
            Thread {
                try {
                    Thread.sleep(1500)
                    Core.rescan()
                } catch (_: Exception) {}
            }.start()
        }
        override fun onAvailable(n: android.net.Network) = go()
        override fun onLinkPropertiesChanged(n: android.net.Network, lp: android.net.LinkProperties) = go()
    }

    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            // screen on / unlock: peers and printers may have been missed while asleep -> look again
            Thread { try { Thread.sleep(800); Core.rescan() } catch (_: Exception) {} }.also { it.isDaemon = true }.start()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") {            // notification "Stop" button: kill server + app
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid()); return START_NOT_STICKY
        }
        val ch = "lanshare"
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "LANShare", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        try { startForeground(1, NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LANShare is running")
            .setContentText("Sharing on your network").setContentIntent(open).setOngoing(true)
            .addAction(R.drawable.ic_notification, "Stop",
                PendingIntent.getService(this, 1, Intent(this, LanShareService::class.java).setAction("STOP"),
                    PendingIntent.FLAG_IMMUTABLE)).build())
        } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException etc.: keep running as a normal service rather than crash
            android.util.Log.w("LANShare", "startForeground refused: " + e)
        }
        if (!started) {
            started = true
            val root = Environment.getExternalStorageDirectory().absolutePath
            try { getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb) }
            catch (_: Exception) {}
            try {
                val f = android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) }
                registerReceiver(screenRx, f)
            } catch (_: Exception) {}
            Thread { Core.start(applicationContext, root) }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(netCb) } catch (_: Exception) {}
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        super.onDestroy()
    }
}
