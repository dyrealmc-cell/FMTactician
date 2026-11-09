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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var panel: View? = null
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

    // Capture session is created once and kept alive (Android 14 forbids reusing the token).
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

    private fun addBubble() {
        val b = TextView(this).apply {
            text = "⚽"; textSize = 24f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E61B5E20")) }
        }
        val lp = overlayParams(dp(52), dp(52), Gravity.TOP or Gravity.START).apply { x = 0; y = dp(200) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved = true
                    lp.x = ox + dx; lp.y = oy + dy; wm.updateViewLayout(b, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(b, lp); bubble = b
    }

    private fun onTap() {
        if (panel != null) { hidePanel(); return }
        val img = last ?: return showPanel("No screen image yet, try again in a second.")
        showPanel("Scanning...")
        val bmp = toBitmap(img)
        ocr.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val squad = getSharedPreferences("squad", Context.MODE_PRIVATE).getStringSet("traits", emptySet()) ?: emptySet()
                showPanel(TacticsEngine.advise(r.text, squad))
            }
            .addOnFailureListener { showPanel("Scan failed: ${it.message}") }
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = (p.rowStride - p.pixelStride * w) / p.pixelStride
        val full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, w, h)
    }

    private fun showPanel(text: String) {
        hidePanel()
        val tv = TextView(this).apply { this.text = text; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val sv = ScrollView(this).apply {
            addView(tv)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#F2101820")) }
            setOnClickListener { hidePanel() }
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt(), Gravity.BOTTOM)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        lp.horizontalMargin = 0.03f
        wm.addView(sv, lp); panel = sv
        tv.setOnClickListener { hidePanel() }
    }

    private fun hidePanel() { panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }; panel = null }

    override fun onDestroy() {
        hidePanel(); bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        last?.close(); vd?.release(); reader?.close(); projection?.stop(); super.onDestroy()
    }
}
