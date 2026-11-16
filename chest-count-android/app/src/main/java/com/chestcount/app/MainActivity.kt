package com.chestcount.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Deliberately uses the platform Activity API for a small, predictable startup path.
 * Screen capture is only started after the user explicitly approves the Android dialog.
 */
class MainActivity : Activity() {
    private var statusView: TextView? = null
    private var startButton: Button? = null
    private var projectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 24)
        window.navigationBarColor = Color.rgb(8, 11, 24)

        try {
            projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            showHomeScreen()
        } catch (error: Throwable) {
            android.util.Log.e("ChestCount", "Home screen failed to initialize", error)
            showStartupError(error)
        }
    }

    private fun showHomeScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 12, 29), Color.rgb(18, 19, 48), Color.rgb(9, 15, 32))
            )
        }

        val brand = TextView(this).apply {
            text = "CHEST COUNT"
            textSize = 21f
            letterSpacing = 0.12f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        root.addView(brand)

        val eyebrow = TextView(this).apply {
            text = "FORTNITE SESSION COMPANION"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(190, 169, 255))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(eyebrow)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(28), dp(20), dp(28))
            background = rounded(
                intArrayOf(Color.rgb(35, 42, 92), Color.rgb(76, 43, 133)),
                dp(22), Color.rgb(130, 91, 255), dp(1)
            )
        }
        val title = TextView(this).apply {
            text = "YOUR CHEST\nCOUNT. AT A GLANCE."
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        hero.addView(title)
        val description = TextView(this).apply {
            text = "A small companion for reviewing your Fortnite chest runs."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(225, 222, 255))
            setPadding(0, dp(12), 0, 0)
        }
        hero.addView(description)
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        val section = TextView(this).apply {
            text = "SESSION CONTROLS"
            textSize = 12f
            letterSpacing = 0.13f
            setTextColor(Color.rgb(194, 178, 255))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(26), 0, dp(10))
        }
        root.addView(section)

        val status = TextView(this).apply {
            text = "Ready. Enable the floating counter permission before starting capture."
            textSize = 14f
            setTextColor(Color.rgb(229, 231, 245))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(intArrayOf(Color.rgb(24, 29, 51)), dp(14), Color.rgb(61, 66, 99), dp(1))
        }
        statusView = status
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val overlayButton = makeButton("ENABLE FLOATING COUNTER", false)
        overlayButton.setOnClickListener { requestOverlayPermission() }
        root.addView(overlayButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(16) })

        val start = makeButton("START SCREEN CAPTURE", true)
        startButton = start
        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                statusView?.text = "Please enable Display over other apps, then return here."
                requestOverlayPermission()
            } else {
                try {
                    val manager = projectionManager
                        ?: getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    statusView?.text = "Waiting for Android screen-capture approval..."
                    @Suppress("DEPRECATION")
                    startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
                } catch (error: Exception) {
                    android.util.Log.e("ChestCount", "Could not request screen capture", error)
                    statusView?.text = "Could not request screen capture: ${error.javaClass.simpleName}"
                }
            }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })

        val stop = makeButton("STOP CAPTURE", false)
        stop.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            statusView?.text = "Capture stopped."
            startButton?.text = "START SCREEN CAPTURE"
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })

        val note = TextView(this).apply {
            text = "Chest recognition is experimental. Always check the result against your recording."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(151, 161, 190))
            setPadding(dp(8), dp(22), dp(8), 0)
        }
        root.addView(note)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            statusView?.text = "Floating counter permission is already enabled."
            return
        }
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (error: Exception) {
            statusView?.text = "Open Settings and allow Chest Count to display over other apps."
        }
    }

    @Deprecated("Uses platform activity result callback for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            statusView?.text = "Screen capture was cancelled. Nothing is recording."
            return
        }
        try {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            statusView?.text = "Capture request sent. Switch to Fortnite after the counter appears."
            startButton?.text = "CAPTURE REQUESTED"
        } catch (error: Exception) {
            android.util.Log.e("ChestCount", "Could not start capture service", error)
            statusView?.text = "Capture could not start: ${error.javaClass.simpleName}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            statusView?.text = "Floating counter permission enabled. You can start capture."
        }
    }

    private fun showStartupError(error: Throwable) {
        val message = "Chest Count couldn't load its home screen.\n\n" +
            error.javaClass.simpleName + ": " + (error.message ?: "No details") +
            "\n\nPlease share this message with support."
        val fallback = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(Color.rgb(12, 14, 28))
        }
        setContentView(fallback)
        Toast.makeText(this, "Chest Count startup error", Toast.LENGTH_LONG).show()
    }

    private fun makeButton(label: String, primary: Boolean): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = if (primary) {
                rounded(intArrayOf(Color.rgb(124, 82, 255), Color.rgb(79, 48, 199)), dp(15))
            } else {
                rounded(intArrayOf(Color.rgb(36, 42, 67), Color.rgb(25, 30, 51)), dp(15), Color.rgb(79, 83, 124), dp(1))
            }
            stateListAnimator = null
        }

    private fun rounded(
        colors: IntArray,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        cornerRadius = radius.toFloat()
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE = 4012
    }
}
