package com.example.chesthud

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

class ChestOverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var countText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var panel: View? = null
    private var count = 0
    private val prefs by lazy { getSharedPreferences("chest_hud", Context.MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(9).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    override fun onCreate() {
        super.onCreate()
        count = prefs.getInt("count", 0)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(7), dp(8), dp(7))
            background = bg(Color.argb(238, 9, 24, 53), Color.rgb(36, 151, 255))
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(Color.rgb(16, 52, 101)); setPadding(dp(7), dp(3), dp(7), dp(3))
        }
        header.addView(text("♜", 20f, Color.rgb(255, 194, 55), true))
        header.addView(text(" CHESTS", 12f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(28), 1f))
        countText = text(count.toString(), 22f, Color.WHITE, true).apply { minWidth = dp(35) }
        header.addView(countText)
        box.addView(header)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0)
        }
        controls.addView(text("−", 20f, Color.WHITE, true).apply {
            background = bg(Color.rgb(24, 63, 111), Color.rgb(36, 151, 255))
            setOnClickListener { change(-1) }
        }, LinearLayout.LayoutParams(dp(48), dp(32)).apply { rightMargin = dp(5) })
        controls.addView(text("+", 20f, Color.WHITE, true).apply {
            background = bg(Color.rgb(24, 92, 166), Color.rgb(36, 151, 255))
            setOnClickListener { change(1) }
        }, LinearLayout.LayoutParams(dp(48), dp(32)))
        box.addView(controls)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(90) }
        header.setOnTouchListener(object : View.OnTouchListener {
            var x0 = 0; var y0 = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { x0=params.x; y0=params.y; tx=e.rawX; ty=e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x=x0+(e.rawX-tx).toInt(); params.y=y0+(e.rawY-ty).toInt()
                        wm.updateViewLayout(box, params); return true
                    }
                }
                return false
            }
        })
        panel = box
        wm.addView(box, params)
    }
    private fun change(delta: Int) {
        count = max(0, count + delta); countText.text = count.toString()
        prefs.edit().putInt("count", count).apply()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }; panel=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
