package com.chestcount.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView
    private lateinit var startButton: Button

    private val captureRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                status.text = "Screen capture is running. Open Fortnite to start counting."
                startButton.text = "CAPTURE RUNNING"
            } else {
                status.text = "Screen capture permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 24)
        window.navigationBarColor = Color.rgb(8, 11, 24)
        window.decorView.systemUiVisibility = 0

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 12, 29), Color.rgb(18, 19, 48), Color.rgb(9, 15, 32))
            )
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_chest_count)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brandRow.addView(brandIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val brandName = TextView(this).apply {
            text = "CHEST COUNT"
            textSize = 18f
            letterSpacing = 0.12f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        }
        brandRow.addView(brandName)
        root.addView(brandRow)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(30), dp(20), dp(28))
            background = rounded(
                intArrayOf(Color.rgb(24, 48, 94), Color.rgb(48, 35, 101), Color.rgb(91, 47, 132)),
                dp(22), Color.rgb(112, 83, 224), dp(1)
            )
        }
        val gameLabel = TextView(this).apply {
            text = "FORTNITE  •  CHEST TRACKER"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(Color.rgb(197, 211, 255))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        hero.addView(gameLabel)
        val headline = TextView(this).apply {
            text = "COUNT EVERY\nCHEST."
            textSize = 34f
            lineSpacing = dp(1).toFloat()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BLACK)
            setPadding(0, dp(12), 0, dp(8))
        }
        hero.addView(headline)
        val subtitle = TextView(this).apply {
            text = "A lightweight counter that stays over your gameplay."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(218, 224, 255))
        }
        hero.addView(subtitle)
        val heroParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) }
        root.addView(hero, heroParams)

        val sectionTitle = TextView(this).apply {
            text = "READY TO DROP IN?"
            textSize = 13f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(196, 181, 253))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(2), dp(26), 0, dp(10))
        }
        root.addView(sectionTitle)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(intArrayOf(Color.rgb(22, 27, 48)), dp(16), Color.rgb(54, 61, 93), dp(1))
        }
        val statusDot = TextView(this).apply {
            text = "●"
            textSize = 17f
            setTextColor(Color.rgb(168, 125, 255))
        }
        statusCard.addView(statusDot)
        status = TextView(this).apply {
            text = "First enable the floating counter, then allow screen capture."
            textSize = 13f
            setTextColor(Color.rgb(225, 229, 245))
            setPadding(dp(10), 0, 0, 0)
        }
        statusCard.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val statusParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(statusCard, statusParams)

        val overlayButton = makeButton("ENABLE FLOATING COUNTER", false)
        overlayButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                status.text = "Floating counter permission is enabled. Start capture when ready."
            } else {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        val overlayParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(16) }
        root.addView(overlayButton, overlayParams)

        startButton = makeButton("START SCREEN CAPTURE", true)
        startButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "Enable 'Display over other apps' first."
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                status.text = "Waiting for Android screen-capture permission..."
                captureRequest.launch(projectionManager.createScreenCaptureIntent())
            }
        }
        val startParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { topMargin = dp(10) }
        root.addView(startButton, startParams)

        val stopButton = makeButton("STOP CAPTURE", false)
        stopButton.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            status.text = "Capture stopped. Your count will reset next time."
            startButton.text = "START SCREEN CAPTURE"
        }
        val stopParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) }
        root.addView(stopButton, stopParams)

        val note = TextView(this).apply {
            text = "Keep Chest Count running, switch to Fortnite, and leave the small counter visible. Detection is still experimental and needs real-game testing."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(151, 161, 190))
            setPadding(dp(8), dp(20), dp(8), 0)
        }
        root.addView(note)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && Settings.canDrawOverlays(this)) {
            status.text = "Floating counter permission is enabled. Start capture when ready."
        }
    }

    private fun makeButton(label: String, primary: Boolean): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            letterSpacing = 0.08f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = if (primary) {
                rounded(intArrayOf(Color.rgb(119, 75, 255), Color.rgb(82, 52, 204)), dp(16))
            } else {
                rounded(intArrayOf(Color.rgb(35, 42, 68), Color.rgb(26, 31, 53)), dp(16), Color.rgb(79, 83, 124), dp(1))
            }
            stateListAnimator = null
            elevation = dp(2).toFloat()
        }

    private fun rounded(
        colors: IntArray,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, colors
    ).apply {
        cornerRadius = radius.toFloat()
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
