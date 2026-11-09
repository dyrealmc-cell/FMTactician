package com.fmtactician

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val REQ = 42

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20*d).toInt(), (40*d).toInt(), (20*d).toInt(), (20*d).toInt()) }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.ic_logo)
            layoutParams = LinearLayout.LayoutParams((110*d).toInt(), (110*d).toInt()) })
        root.addView(TextView(this).apply { text = "FM Tactician"; textSize = 26f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { textSize = 15f; setPadding(0, (12*d).toInt(), 0, (12*d).toInt())
            text = "Setup, once:\n1. Allow display over other apps.\n2. Start the bubble.\n\n" +
                "In Football Manager, tap the ⚽ bubble and use the side window:\n" +
                "☐ My Team: open your team overview, tap My Team\n" +
                "☐ Lineup: open your lineup, tap Lineup\n" +
                "☐ Player: open each player's profile, tap Player (repeat)\n" +
                "☐ Opponent: open the opponent's report, tap Opponent for Attack, Defence, Shape and Set Piece advice." })
        root.addView(Button(this).apply { text = "1. Allow display over other apps"; setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
        root.addView(Button(this).apply { text = "2. Start bubble"; setOnClickListener { start() } })
        root.addView(Button(this).apply { text = "Reset saved team data"; setOnClickListener {
            Store(this@MainActivity).clear(); toast("Saved data cleared") } })
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
