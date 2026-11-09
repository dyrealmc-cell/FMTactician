package com.fmtactician

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val st by lazy { Store(this) }
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var outTv: TextView? = null
    private var statusTv: TextView? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var last: Image? = null
    private var w = 0; private var h = 0
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        goForeground()
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data != null && projection == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
            startCapture()
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (bubble == null) addBubble()
        if (panel == null) addPanel()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fm", "FM Tactician", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "fm").setContentTitle("FM Tactician running")
            .setSmallIcon(android.R.drawable.ic_menu_compass).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun realSize(): DisplayMetrics {
        val dm = DisplayMetrics()
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        return dm
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ x ->
            val img = x.acquireLatestImage() ?: return@setOnImageAvailableListener
            last?.close(); last = img
        }, handler)
        return r
    }

    // One capture session kept alive (Android 14 forbids reusing the token).
    private fun startCapture() {
        val dm = realSize(); w = dm.widthPixels; h = dm.heightPixels
        reader = newReader()
        vd = projection!!.createVirtualDisplay("fmscan", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
    }

    // FM runs in landscape: resize the capture when the screen rotates.
    private fun ensureSize(): Boolean {
        val dm = realSize()
        if (dm.widthPixels == w && dm.heightPixels == h) return false
        w = dm.widthPixels; h = dm.heightPixels
        last?.close(); last = null
        val old = reader
        reader = newReader()
        vd?.resize(w, h, dm.densityDpi)
        vd?.surface = reader!!.surface
        old?.close()
        return true
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(wd: Int, ht: Int, grav: Int) = WindowManager.LayoutParams(wd, ht,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = grav }

    private fun tv(txt: String, size: Float, click: (() -> Unit)? = null) = TextView(this).apply {
        text = txt; textSize = size; setTextColor(Color.WHITE); setPadding(dp(8), dp(6), dp(8), dp(6))
        if (click != null) setOnClickListener { click() }
    }

    private fun btn(txt: String, click: () -> Unit) = tv(txt, 13f, click).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#6A1BD9")) }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }

    private fun addBubble() {
        val b = TextView(this).apply {
            text = "⚽"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E61B5E20")) }
        }
        val lp = overlayParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { x = 0; y = dp(120) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved = true
                    lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(b, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        wm.addView(b, lp); bubble = b
    }

    private fun panelHeight(): Int {
        val hh = if (h > 0) h else resources.displayMetrics.heightPixels
        return minOf(dp(380), (hh * 0.85).toInt())
    }

    private fun addPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#F2101820")) }
            setPadding(dp(4), dp(4), dp(4), dp(4)); visibility = View.GONE
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(tv("FM Tactician", 14f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        head.addView(tv("⇄", 16f) { flip() })
        head.addView(tv("✕", 16f) { root.visibility = View.GONE })
        root.addView(head)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Scan") { scan() })
        row.addView(btn("Advice") { st.reload(); say(Advisor.advise(st)) })
        row.addView(btn("New match") { st.reload(); st.clearOpp(); refresh(); say("Opponent data cleared. Open the next opponent's Hub or Report and tap Scan.") })
        root.addView(row)
        val s = tv("", 11f); statusTv = s
        root.addView(s)
        val o = tv("Open any Tactics page, Pre-Match Hub, Next Match Report or a Player Profile, then tap Scan. The screen type is detected automatically.", 12f)
        outTv = o
        root.addView(ScrollView(this).apply { addView(o) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val lp = overlayParams(dp(220), panelHeight(), Gravity.END or Gravity.CENTER_VERTICAL)
        wm.addView(root, lp); panel = root; panelLp = lp; refresh()
    }

    private fun refresh() { st.reload(); statusTv?.text = Advisor.status(st) }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refresh()
    }

    private fun flip() {
        val lp = panelLp ?: return
        lp.gravity = if ((lp.gravity and Gravity.END) == Gravity.END) Gravity.START or Gravity.CENTER_VERTICAL
                     else Gravity.END or Gravity.CENTER_VERTICAL
        wm.updateViewLayout(panel, lp)
    }

    private fun say(s: String) {
        outTv?.text = s
        panel?.visibility = View.VISIBLE
        (outTv?.parent as? ScrollView)?.scrollTo(0, 0)
    }

    // Hide our own windows so the screenshot only contains the game, then OCR it.
    private fun scan() {
        val resized = ensureSize()
        if (resized) panelLp?.let { it.height = panelHeight(); wm.updateViewLayout(panel, it) }
        val wasOpen = panel?.visibility == View.VISIBLE
        bubble?.visibility = View.INVISIBLE; panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            fun restore() { bubble?.visibility = View.VISIBLE; panel?.visibility = if (wasOpen) View.VISIBLE else View.GONE }
            val img = last
            if (img == null) { restore(); say("Screen not ready yet. Wait a second and tap Scan again."); return@postDelayed }
            val bmp = toBitmap(img)
            ocr.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r -> restore(); handle(r, bmp) }
                .addOnFailureListener { restore(); say("Scan failed: ${it.message}") }
        }, if (resized) 1300L else 700L)
    }

    private fun handle(r: Text, bmp: Bitmap) {
        st.reload()
        val lines = r.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { Ln(l.text.trim(), it.left, it.top, it.width(), it.height()) } }
        val blocks = r.textBlocks.mapNotNull { b -> b.boundingBox?.let { Ln(b.text.replace("\n", " ").trim(), it.left, it.top, it.width(), it.height()) } }
        val s = Scan(lines, blocks, r.text, bmp.width, bmp.height, bmp)
        val kind = Parse.screen(r.text)
        val team = Parse.teamName(s)
        if (kind != "unknown" && kind != "player" && team.isNotBlank()) st.d.put("team", team)
        val msg = when (kind) {
            "formation" -> Parse.lineup(s, st)
            "shape", "defence", "attack" -> Parse.tactic(s, kind, st)
            "setpieces" -> "SET PIECES screen noted. The advice includes set-piece suggestions; use the Takers list to pick your best penalty taker."
            "player" -> Parse.player(s, st)
            "hub", "report" -> Parse.opp(s, st, kind == "hub") + "\n\n" + Advisor.advise(st)
            else -> "Screen not recognised. Open Tactics (Formation, Shape, Defence or Attack), the Pre-Match Hub, Next Match > Report, or a Player Profile, then tap Scan."
        }
        st.save()
        say(msg); refresh()
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = (p.rowStride - p.pixelStride * w) / p.pixelStride
        val full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, w, h)
    }

    override fun onDestroy() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        last?.close(); vd?.release(); reader?.close(); projection?.stop(); super.onDestroy()
    }
}
