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
    private var autoBtn: TextView? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var last: Image? = null
    private var w = 0; private var h = 0
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Automatic scanning
    private var autoOn = true
    private var busy = false
    private var scanning = false
    private var prevS: IntArray? = null
    private var doneS: IntArray? = null
    private val AUTO = setOf("formation", "squad", "role", "shape", "defence", "attack", "player")
    private val CLUB_KINDS = setOf("formation", "squad", "hub")
    private var changed = true
    private var idle = 0
    private var lastSig = ""
    private val ticker = object : Runnable {
        override fun run() {
            try { autoTick() } catch (_: Exception) { busy = false }
            handler.postDelayed(this, 800)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        goForeground()
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data != null && projection == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, handler)
            startCapture()
            handler.removeCallbacks(ticker); handler.postDelayed(ticker, 1500)
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
        prevS = null; doneS = null
        val old = reader
        reader = newReader()
        vd?.resize(w, h, dm.densityDpi)
        vd?.surface = reader!!.surface
        old?.close()
        return true
    }

    // ---------- Automatic scanning ----------
    // Looks at a coarse grid of the screen. When the screen has settled after a change
    // (so not during live play) and it is a new screen, it is read once.
    private fun sample(img: Image): IntArray {
        val p = img.planes[0]; val buf = p.buffer
        val out = IntArray(64 * 36)
        for (j in 0 until 36) for (i in 0 until 64) {
            val x = ((i + 0.5) * w / 64).toInt(); val y = ((j + 0.5) * h / 36).toInt()
            val idx = y * p.rowStride + x * p.pixelStride
            out[j * 64 + i] = ((buf.get(idx).toInt() and 0xFF) + (buf.get(idx + 1).toInt() and 0xFF) + (buf.get(idx + 2).toInt() and 0xFF)) / 3
        }
        return out
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += Math.abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    // Reads a screen once after it has settled following a change. Pages that look alike
    // (Shape, Defence, Attack, one player's profile and the next) are told apart by what is read.
    private fun autoTick() {
        if (!autoOn || busy || scanning) return
        val p = panel
        if (p == null || p.visibility == View.VISIBLE) { prevS = null; changed = true; return }
        val img = last ?: return
        val s = sample(img)
        val pv = prevS; prevS = s
        if (pv == null) return
        if (diff(s, pv) > 0.5) { changed = true; idle = 0; return }
        idle++
        if (!changed && idle < 20) return
        changed = false; idle = 0
        busy = true
        val bmp = toBitmap(img)
        ocr.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                busy = false
                val kind = Parse.screen(r.text)
                if (kind !in AUTO) return@addOnSuccessListener
                val sig = kind + r.text.hashCode()
                if (sig == lastSig) return@addOnSuccessListener
                lastSig = sig
                val m = process(r, bmp, true)
                if (m != null) { outTv?.text = m; refresh(); flash() }
            }
            .addOnFailureListener { busy = false }
    }

    private fun flash() {
        bubble?.text = "✓"
        handler.postDelayed({ bubble?.text = "⚽" }, 1500)
    }

    private fun toggleAuto() {
        autoOn = !autoOn; prevS = null; doneS = null
        autoBtn?.text = if (autoOn) "Auto: ON" else "Auto: OFF"
        say(if (autoOn) "Auto ON: close this window and browse. Squad, tactics and player screens are saved by themselves (the bubble shows ✓). Opponent and match screens need Scan." else "Auto OFF: use Scan for everything.")
    }

    // ---------- UI ----------
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
        head.addView(tv("✕", 16f) { root.visibility = View.GONE; changed = true })
        root.addView(head)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btn("Scan") { scan() })
        row1.addView(btn("Advice") { st.reload(); say(Advisor.advise(st)) })
        row1.addView(btn("Roles") { st.reload(); say(Advisor.roles(st)) })
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ab = btn("Auto: ON") { toggleAuto() }; autoBtn = ab
        row2.addView(ab)
        row2.addView(btn("New match") { st.reload(); st.clearOpp(); refresh(); say("Opponent data cleared. Open the next opponent's Hub or Report and tap Scan.") })
        root.addView(row2)
        val s = tv("", 11f); statusTv = s
        root.addView(s)
        val o = tv("Auto is ON. Close this window (✕) and just browse: Club Squad, role windows, tactics pages and player profiles are saved by themselves (the bubble shows ✓). Auto pauses while this window is open. For the opponent (Hub or Report) and live match stats, open the screen and tap Scan.", 12f)
        outTv = o
        root.addView(ScrollView(this).apply { addView(o) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val lp = overlayParams(dp(220), panelHeight(), Gravity.END or Gravity.CENTER_VERTICAL)
        wm.addView(root, lp); panel = root; panelLp = lp; refresh()
    }

    private fun refresh() { st.reload(); statusTv?.text = Advisor.status(st) }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        changed = true
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

    // ---------- Manual scan ----------
    // Hide our own windows so the screenshot only contains the game, then OCR it.
    private fun scan() {
        scanning = true
        val resized = ensureSize()
        if (resized) panelLp?.let { it.height = panelHeight(); wm.updateViewLayout(panel, it) }
        val wasOpen = panel?.visibility == View.VISIBLE
        bubble?.visibility = View.INVISIBLE; panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            fun restore() { bubble?.visibility = View.VISIBLE; panel?.visibility = if (wasOpen) View.VISIBLE else View.GONE }
            val img = last
            if (img == null) { scanning = false; restore(); say("Screen not ready yet. Wait a second and tap Scan again."); return@postDelayed }
            val bmp = toBitmap(img)
            ocr.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r -> scanning = false; restore(); say(process(r, bmp, false) ?: ""); refresh() }
                .addOnFailureListener { scanning = false; restore(); say("Scan failed: ${it.message}") }
        }, if (resized) 1300L else 700L)
    }

    /** Reads a screen. In auto mode only squad, role, tactics and player screens are saved; returns null when skipped. */
    private fun process(r: Text, bmp: Bitmap, auto: Boolean): String? {
        val kind = Parse.screen(r.text)
        if (auto && kind !in AUTO) return null
        st.reload()
        val lines = r.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { Ln(l.text.trim(), it.left, it.top, it.width(), it.height()) } }
        val blocks = r.textBlocks.mapNotNull { b -> b.boundingBox?.let { Ln(b.text.replace("\n", " ").trim(), it.left, it.top, it.width(), it.height()) } }
        val s = Scan(lines, blocks, r.text, bmp.width, bmp.height, bmp)
        var note = ""
        // Works for any club: the club is learned from the screens, and a different club (seen twice) starts fresh.
        val team = Parse.teamName(s)
        if (team.isNotBlank() && kind in CLUB_KINDS) {
            val old = st.str("team")
            if (old.isBlank()) st.d.put("team", team)
            else if (!Parse.sameClub(team, old)) {
                if (st.str("pendTeam") == team) { st.clear(); st.d.put("team", team); note = "New club detected: $team. Previous data cleared.\n\n"; lastSig = "" }
                else st.d.put("pendTeam", team)
            } else st.d.remove("pendTeam")
        }
        val msg = when (kind) {
            "formation" -> Parse.lineup(s, st)
            "squad" -> Parse.squad(s, st)
            "role" -> Parse.role(s, st)
            "shape", "defence", "attack" -> Parse.tactic(s, kind, st)
            "setpieces" -> "SET PIECES screen noted. The advice includes set-piece suggestions; use the Takers list to pick your best penalty taker."
            "player" -> Parse.player(s, st)
            "match" -> Parse.match(s, st)
            "matchgfx" -> "This is a graphic screen. Open the match Stats overview (possession, shots, clear-cut chances) and tap Scan."
            "hub", "report" -> Parse.opp(s, st, kind == "hub") + "\n\n" + Advisor.advise(st)
            else -> "Screen not recognised. Open Club Squad, Tactics (Formation, Shape, Defence or Attack), the Pre-Match Hub, Next Match > Report, the match Stats overview, a role window or a Player Profile, then tap Scan."
        }
        st.save()
        return note + msg
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val buf = p.buffer; buf.rewind()
        val pad = (p.rowStride - p.pixelStride * w) / p.pixelStride
        val full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buf)
        return Bitmap.createBitmap(full, 0, 0, w, h)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        last?.close(); vd?.release(); reader?.close(); projection?.stop(); super.onDestroy()
    }
}
