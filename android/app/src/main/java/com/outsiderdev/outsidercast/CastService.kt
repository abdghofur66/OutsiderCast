package com.outsiderdev.outsidercast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.ByteArrayOutputStream

/** Layanan latar depan: merekam layar (MediaProjection) dan menyajikannya lewat HttpServer. */
class CastService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CH = "cast"
        private const val NID = 7
        @Volatile var instance: CastService? = null
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var http: HttpServer? = null
    private var lastFrameAt = 0L
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        worker = HandlerThread("oc-capture").apply { start() }
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)?.also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        if (projection == null) { stopSelf(); return START_NOT_STICKY }

        if (http == null) { http = HttpServer(this); Cast.port = http!!.start() }
        acquireLocks()
        setupDisplay()
        Cast.live = true
        Cast.paused = false
        return START_NOT_STICKY
    }

    /** Ukuran virtual display mengikuti orientasi saat ini dan kualitas pilihan. */
    private fun setupDisplay() {
        val m = resources.displayMetrics
        val longSide = maxOf(m.widthPixels, m.heightPixels)
        val scale = minOf(1f, Cast.quality.longSide.toFloat() / longSide)
        val w = ((m.widthPixels * scale).toInt() / 2) * 2
        val h = ((m.heightPixels * scale).toInt() / 2) * 2

        val old = reader
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r -> onImage(r) }, handler)
        }
        if (display == null) {
            display = projection?.createVirtualDisplay("OutsiderCast", w, h, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        } else {
            display?.resize(w, h, m.densityDpi)
            display?.surface = reader!!.surface
        }
        old?.close()
    }

    fun applyQuality() = handler.post { if (projection != null) setupDisplay() }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            // Hemat baterai: hanya encode saat ada penonton, tidak dijeda, dan sesuai batas fps
            if (Cast.paused || Cast.viewers.isEmpty() || now - lastFrameAt < 1000L / Cast.quality.fps) return
            lastFrameAt = now
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val crop = if (rowPad > 0) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
            val bos = ByteArrayOutputStream(200_000)
            crop.compress(Bitmap.CompressFormat.JPEG, Cast.quality.jpeg, bos)
            if (crop !== bmp) crop.recycle()
            bmp.recycle()
            Cast.publish(bos.toByteArray())
        } catch (_: Exception) {
        } finally { img.close() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layar diputar: sesuaikan ukuran agar gambar tidak gepeng
        handler.postDelayed({ if (projection != null) setupDisplay() }, 300)
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
        Cast.wakeAll()
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        http?.stop()
        try { wake?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        worker.quitSafely()
        instance = null
        super.onDestroy()
    }
}
