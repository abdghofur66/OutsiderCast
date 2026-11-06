package com.outsiderdev.outsidercast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*

/** Layanan latar depan: menjalankan RtcEngine (video layar) dan HttpServer (halaman + signaling). */
class CastService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CH = "cast"
        private const val NID = 7
        @Volatile var instance: CastService? = null
    }

    @Volatile var engine: RtcEngine? = null; private set
    private var http: HttpServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || engine != null) { if (engine == null) stopSelf(); return START_NOT_STICKY }

        engine = try { RtcEngine(this, data) { main.post { stopSelf() } } } catch (e: Exception) { null }
        if (engine == null) { stopSelf(); return START_NOT_STICKY }
        if (http == null) { http = HttpServer(this); Cast.port = http!!.start() }
        acquireLocks()
        Cast.paused = false
        Cast.live = true
        return START_NOT_STICKY
    }

    fun applyQuality() { engine?.reformat() }
    fun applyPause() { engine?.setPaused(Cast.paused) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({ engine?.reformat() }, 300) // layar diputar
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Berbagi layar", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setContentTitle("OutsiderCast sedang berbagi layar")
            .setContentText("Ketuk untuk membuka. Penonton melihat layar HP ini.")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Berhenti", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

    private fun acquireLocks() {
        if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OutsiderCast:cast").apply { acquire(6 * 60 * 60 * 1000L) }
        if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OutsiderCast").apply { acquire() }
    }

    override fun onDestroy() {
        Cast.live = false
        Cast.paused = false
        http?.stop(); http = null
        engine?.release(); engine = null
        Cast.viewers.clear()
        try { wake?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
