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
    private var rowViews = listOf<TextView>()
    private var oppDone = false
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var last: Image? = null
    private var w = 0; private var h = 0
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val KINDS = listOf("team" to "My Team", "lineup" to "Lineup", "player" to "Player", "opp" to "Opponent")

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

    // One capture session kept alive (Android 14 forbids reusing the token).
    private fun startCapture() {
        val dm = resources.displayMetrics
        w = dm.widthPixels; h = dm.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            last?.close(); last = img
        }, handler)
        vd = projection!!.createVirtualDisplay("fmscan", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun overlayParams(wd: Int, ht: Int, grav: Int) = WindowManager.LayoutParams(wd, ht,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = grav }

    private fun tv(txt: String, size: Float, click: (() -> Unit)? = null) = TextView(this).apply {
        text = txt; textSize = size; setTextColor(Color.WHITE); setPadding(dp(8), dp(7), dp(8), dp(7))
        if (click != null) setOnClickListener { click() }
    }

    private fun addBubble() {
        val b = TextView(this).apply {
            text = "⚽"; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E61B5E20")) }
        }
        val lp = overlayParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { x = 0; y = dp(200) }
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
        rowViews = KINDS.map { (k, _) -> tv("", 13f) { scan(k) } }
        rowViews.forEach { root.addView(it) }
        val o = tv("Open the matching FM screen, then tap a row above.", 12f)
        outTv = o
        root.addView(ScrollView(this).apply { addView(o) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val lp = overlayParams(dp(230), minOf(dp(340), (h.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).let { (it * 0.8).toInt() }),
            Gravity.END or Gravity.CENTER_VERTICAL)
        wm.addView(root, lp); panel = root; panelLp = lp; refreshRows()
    }

    private fun refreshRows() {
        val n = st.players().length()
        val done = listOf(st.team.isNotBlank() || st.teamStr.isNotEmpty(), st.lineup.isNotEmpty(), n > 0, oppDone)
        rowViews.forEachIndexed { i, v ->
            val label = KINDS[i].second + (if (i == 2) " ($n saved)" else "")
            v.text = (if (done[i]) "☑ " else "☐ ") + (i + 1) + ". " + label
        }
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshRows()
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
    private fun scan(kind: String) {
        if (last == null) { say("No screen image yet. Wait a second and try again."); return }
        val wasOpen = panel?.visibility == View.VISIBLE
        bubble?.visibility = View.INVISIBLE; panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            fun restore() { bubble?.visibility = View.VISIBLE; panel?.visibility = if (wasOpen) View.VISIBLE else View.GONE }
            val img = last
            if (img == null) { restore(); return@postDelayed }
            val bmp = toBitmap(img)
            ocr.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r -> restore(); handle(kind, r) }
                .addOnFailureListener { restore(); say("Scan failed: ${it.message}") }
        }, 600)
    }

    private fun handle(kind: String, r: Text) {
        val lines = r.textBlocks.flatMap { it.lines }.map { Ln(it.text.trim(), it.boundingBox?.height() ?: 0, it.boundingBox?.top ?: 0) }
        val text = r.text
        when (kind) {
            "team" -> {
                val name = Engine.guessName(lines, h); val (s, wk) = Engine.sw(text); val f = Engine.formation(text)
                if (name.isNotBlank()) st.team = name
                if (s.isNotEmpty() || wk.isNotEmpty()) { st.teamStr = s; st.teamWeak = wk }
                if (f.isNotBlank()) st.formation = f
                say("MY TEAM saved\nName: ${name.ifBlank { "(not read, try the team overview screen)" }}\n" +
                    "Strengths: ${s.joinToString().ifBlank { "-" }}\nWeaknesses: ${wk.joinToString().ifBlank { "-" }}")
            }
            "lineup" -> {
                val f = Engine.formation(text); val n = Engine.names(lines)
                if (f.isNotBlank()) st.formation = f
                if (n.isNotEmpty()) st.lineup = n
                say("LINEUP saved\nFormation: ${f.ifBlank { "-" }}\nPlayers: ${n.joinToString().ifBlank { "-" }}")
            }
            "player" -> {
                val name = Engine.guessName(lines, h); val (s, wk) = Engine.sw(text)
                if (name.isBlank()) say("Couldn't read the player's name. Open the player's profile and try again.")
                else {
                    st.savePlayer(name, s, wk)
                    say("PLAYER saved: $name\nStrengths: ${s.joinToString().ifBlank { "-" }}\nWeaknesses: ${wk.joinToString().ifBlank { "-" }}")
                }
            }
            else -> { oppDone = true; say(Engine.advise(text, lines, h, st)) }
        }
        refreshRows()
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
