package com.lanshare.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.net.Inet4Address

class ServerService : Service() {
    private var multicast: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        if (!started) {
            started = true
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wm.createMulticastLock("lanshare").apply { setReferenceCounted(false); acquire() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanshare:srv").apply { acquire() }
            Thread {
                try {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                    port = Python.getInstance().getModule("bridge")
                        .callAttr("start", rootDir(), cfgPath(this), Build.MODEL ?: "Android", linkSpec(this)).toInt()
                    watchNetworks()
                } catch (e: Throwable) {
                    error = e.toString()
                }
            }.start()
        }
        return START_STICKY
    }

    /** Push IP/prefix pairs from LinkProperties into Python whenever the network changes (Android 10+ restricts ioctl/ARP). */
    private fun watchNetworks() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(n: Network, lp: android.net.LinkProperties) = push()
            override fun onAvailable(n: Network) = push()
            override fun onLost(n: Network) = push()
            private fun push() {
                try { Python.getInstance().getModule("bridge").callAttr("set_ifaces", linkSpec(this@ServerService)) } catch (_: Throwable) {}
            }
        }
        netCb = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Throwable) {}
        // hotspot (tethering) interface is not the default network, so also listen to everything
        try { cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCb!!)
        } catch (_: Throwable) {}
        try { Python.getInstance().getModule("bridge").callAttr("stop") } catch (_: Throwable) {}
        multicast?.release(); wake?.release()
        started = false; port = 0
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("ls", "LANShare", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "ls") else Notification.Builder(this)
        return b.setContentTitle("LANShare running").setContentText("Visible to devices on your network")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    companion object {
        const val ACTION_STOP = "com.lanshare.app.STOP"
        const val ACTION_STOPPED = "com.lanshare.app.STOPPED"
        @Volatile var port = 0
        @Volatile var error: String? = null
        @Volatile var started = false

        fun rootDir(): String = Environment.getExternalStorageDirectory().absolutePath
        fun cfgPath(ctx: Context): String = File(ctx.filesDir, ".lanshare.json").absolutePath

        /** "ip/prefix,ip/prefix" for every IPv4 address Android knows about (Wi-Fi, hotspot, ethernet). */
        fun linkSpec(ctx: Context): String {
            val cm = ctx.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val out = LinkedHashSet<String>()
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress)
                        out.add("${a.hostAddress}/${la.prefixLength}")
                }
            }
            return out.joinToString(",")
        }
    }
}
