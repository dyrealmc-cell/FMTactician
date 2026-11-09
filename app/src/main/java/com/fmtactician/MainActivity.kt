package com.fmtactician

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("squad", Context.MODE_PRIVATE) }
    private val REQ = 42

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20*d).toInt(), (40*d).toInt(), (20*d).toInt(), (20*d).toInt()) }
        root.addView(TextView(this).apply { text = "FM Tactician"; textSize = 26f })
        root.addView(TextView(this).apply { text = "1. Tick your squad's strengths:"; setPadding(0, (16*d).toInt(), 0, 0) })
        val saved = prefs.getStringSet("traits", emptySet()) ?: emptySet()
        SQUAD_TRAITS.forEach { (k, label) ->
            root.addView(CheckBox(this).apply {
                text = label; isChecked = k in saved
                setOnCheckedChangeListener { _, on ->
                    val s = (prefs.getStringSet("traits", emptySet()) ?: emptySet()).toMutableSet()
                    if (on) s.add(k) else s.remove(k)
                    prefs.edit().putStringSet("traits", s).apply()
                }
            })
        }
        root.addView(Button(this).apply { text = "2. Allow display over other apps"; setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
        root.addView(Button(this).apply { text = "3. Start bubble"; setOnClickListener { start() } })
        root.addView(TextView(this).apply { text = "Then open Football Manager, go to the opponent's scouting screen and tap the ⚽ bubble."; setPadding(0, (12*d).toInt(), 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) { toast("Allow overlay permission first"); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == REQ && res == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)
                .putExtra("code", res).putExtra("data", data))
            toast("Bubble started. Open Football Manager.")
            moveTaskToBack(true)
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
