package com.outsiderdev.outsidercast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private val REQ = 41
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var url: TextView
    private lateinit var hint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var count: TextView
    private lateinit var list: TextView
    private var shownUrl = ""

    private val BG = Color.parseColor("#0F172A")
    private val CARD = Color.parseColor("#111C33")
    private val ACC = Color.parseColor("#0284C7")
    private val TXT = Color.parseColor("#E2E8F0")
    private val MUTED = Color.parseColor("#94A3B8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        setContentView(build())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int = 14) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun text(s: String, size: Float = 15f, color: Int = TXT, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(s: String, color: Int = Color.parseColor("#1E293B")) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); background = round(color, 10)
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(CARD); setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(14)) }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16)) }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(0, 0, dp(10), 0) })
        head.addView(text("OutsiderCast", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        status = text("Belum berbagi", 13f, MUTED).apply { setPadding(dp(12), dp(5), dp(12), dp(5)); background = round(Color.parseColor("#1E293B"), 99) }
        head.addView(status)
        root.addView(head)
        root.addView(text("Share screen lokal untuk presentasi & mengajar", 13f, MUTED).apply { setPadding(0, dp(2), 0, dp(16)) })

        // Kartu bergabung
        val join = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        join.addView(text("Cara bergabung", 15f, bold = true))
        qr = ImageView(this).apply { background = round(Color.WHITE, 10); setPadding(dp(8), dp(8), dp(8), dp(8)); visibility = View.GONE }
        join.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)).apply { setMargins(0, dp(12), 0, 0) })
        url = text("Mulai berbagi untuk menampilkan alamat", 17f, Color.parseColor("#38BDF8")).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)); setTextIsSelectable(true); typeface = Typeface.MONOSPACE
        }
        join.addView(url)
        hint = text("Penonton harus satu Wi-Fi atau tersambung ke hotspot HP ini, lalu scan QR atau buka alamat di browser.", 12f, MUTED).apply { gravity = Gravity.CENTER }
        join.addView(hint)
        root.addView(join)

        // Kontrol
        val ctl = card()
        val row = LinearLayout(this)
        btnStart = button("▶ Mulai berbagi", ACC).apply { setOnClickListener { toggle() } }
        btnPause = button("⏸ Jeda").apply { isEnabled = false; alpha = .5f; setOnClickListener { Cast.paused = !Cast.paused; CastService.instance?.applyPause(); refresh() } }
        row.addView(btnStart); row.addView(btnPause)
        ctl.addView(row)

        ctl.addView(text("Kualitas", 13f, MUTED).apply { setPadding(0, dp(14), 0, dp(4)) })
        val qs = Cast.Quality.values()
        val spin = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, qs.map { it.label }) {
                override fun getView(p: Int, v: View?, g: android.view.ViewGroup) = (super.getView(p, v, g) as TextView).apply { setTextColor(TXT) }
            }
            setSelection(qs.indexOf(Cast.quality))
            background = round(Color.parseColor("#1E293B"), 10)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (Cast.quality != qs[pos]) { Cast.quality = qs[pos]; CastService.instance?.applyQuality() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        ctl.addView(spin, LinearLayout.LayoutParams(-1, dp(46)))

        ctl.addView(text("PIN ruang (opsional)", 13f, MUTED).apply { setPadding(0, dp(14), 0, dp(4)) })
        val pinRow = LinearLayout(this)
        val pin = EditText(this).apply {
            hint = "Kosongkan = tanpa PIN"; setHintTextColor(Color.parseColor("#64748B")); setTextColor(TXT)
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            background = round(Color.parseColor("#1E293B"), 10); setPadding(dp(12), 0, dp(12), 0); setText(Cast.pin)
        }
        pinRow.addView(pin, LinearLayout.LayoutParams(0, dp(46), 1f))
        val save = Button(this).apply {
            text = "Simpan"; isAllCaps = false; setTextColor(Color.WHITE); background = round(Color.parseColor("#1E293B"), 10)
            setOnClickListener {
                Cast.pin = pin.text.toString().trim()
                Toast.makeText(this@MainActivity, if (Cast.pin.isEmpty()) "Tanpa PIN" else "PIN aktif", Toast.LENGTH_SHORT).show()
            }
        }
        pinRow.addView(save, LinearLayout.LayoutParams(-2, dp(46)).apply { setMargins(dp(8), 0, 0, 0) })
        ctl.addView(pinRow)
        val snd = CheckBox(this).apply {
            text = if (Build.VERSION.SDK_INT >= 29) "Kirim suara HP (YouTube, video, musik)" else "Kirim suara (butuh Android 10+)"
            setTextColor(TXT); isChecked = Cast.audioOn && Build.VERSION.SDK_INT >= 29; isEnabled = Build.VERSION.SDK_INT >= 29
            buttonTintList = android.content.res.ColorStateList.valueOf(ACC)
            setOnCheckedChangeListener { _, v -> Cast.audioOn = v }
        }
        ctl.addView(snd, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, 0) })
        root.addView(ctl)

        // Penonton
        val vw = card()
        count = text("Penonton: 0", 15f, bold = true)
        list = text("Belum ada penonton", 14f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        vw.addView(count); vw.addView(list)
        root.addView(vw)

        // Footer standar OutsiderDev
        val foot = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10)) }
        foot.addView(text("Developed by ", 13f, MUTED))
        val link = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://outsiderdev.com"))) }
        }
        link.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(0, 0, dp(5), 0) })
        link.addView(text("OutsiderDev", 13f, TXT, bold = true))
        foot.addView(link)
        root.addView(foot)

        return ScrollView(this).apply { setBackgroundColor(BG); addView(root) }
    }

    private fun toggle() {
        if (Cast.live) {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
            ui.postDelayed({ refresh() }, 300)
        } else if (Cast.audioOn && Build.VERSION.SDK_INT >= 29 &&
                   checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Izin "rekam audio" dibutuhkan Android untuk menangkap suara yang diputar di HP
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
        } else askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Tanpa izin audio, hanya gambar yang terkirim", Toast.LENGTH_LONG).show()
            askProjection()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Izin rekam layar ditolak", Toast.LENGTH_SHORT).show(); return
        }
        val i = Intent(this, CastService::class.java).putExtra(CastService.EXTRA_CODE, resultCode).putExtra(CastService.EXTRA_DATA, data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun refresh() {
        val live = Cast.live
        status.text = if (!live) "Belum berbagi" else if (Cast.paused) "Dijeda" else "● Sedang berbagi"
        status.setTextColor(Color.parseColor(if (!live) "#94A3B8" else if (Cast.paused) "#FDE68A" else "#86EFAC"))
        status.background = round(Color.parseColor(if (!live) "#1E293B" else if (Cast.paused) "#713F12" else "#14532D"), 99)
        btnStart.text = if (live) "⏹ Berhenti" else "▶ Mulai berbagi"
        btnStart.background = round(if (live) Color.parseColor("#7F1D1D") else ACC, 10)
        btnPause.isEnabled = live; btnPause.alpha = if (live) 1f else .5f
        btnPause.text = if (Cast.paused) "▶ Lanjutkan" else "⏸ Jeda"

        val addr = Cast.lanAddresses().firstOrNull()?.second
        val u = if (live && addr != null && Cast.port > 0) "http://$addr:${Cast.port}" else ""
        if (u != shownUrl) {
            shownUrl = u
            if (u.isEmpty()) {
                qr.visibility = View.GONE
                url.text = if (addr == null) "Tidak terhubung ke Wi-Fi/hotspot" else "Mulai berbagi untuk menampilkan alamat"
            } else { url.text = u; qr.setImageBitmap(makeQr(u)); qr.visibility = View.VISIBLE }
        }
        val names = Cast.viewers.values.toList()
        count.text = "Penonton: ${names.size}"
        list.text = if (names.isEmpty()) "Belum ada penonton" else names.joinToString("\n") { "• $it" }
    }

    private fun makeQr(s: String): Bitmap {
        val m = QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, 512, 512, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(512 * 512) { if (m[it % 512, it / 512]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, 512, 512, Bitmap.Config.RGB_565)
    }

    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }
    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
