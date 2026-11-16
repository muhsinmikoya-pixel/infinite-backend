package com.chestcount.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private var statusView: TextView? = null
    private var countView: TextView? = null
    private var startButton: Button? = null
    private var editButton: Button? = null
    private var projectionManager: MediaProjectionManager? = null
    private var captureActive = false
    private var editMode = false
    private var receiverRegistered = false
    private lateinit var store: ChestCountStore
    private var historyContainer: LinearLayout? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CaptureService.ACTION_STATUS) return
            val state = intent.getStringExtra(CaptureService.EXTRA_STATUS_STATE) ?: return
            statusView?.text = intent.getStringExtra(CaptureService.EXTRA_STATUS_MESSAGE) ?: "Status updated."
            countView?.text = intent.getIntExtra(CaptureService.EXTRA_COUNT, store.currentCount()).toString()
            editMode = intent.getBooleanExtra(CaptureService.EXTRA_EDIT_MODE, editMode)
            editButton?.text = if (editMode) "FINISH POSITIONING" else "MOVE COUNTER"
            when (state) {
                CaptureService.STATUS_RUNNING -> {
                    captureActive = true
                    startButton?.text = "CAPTURE RUNNING"
                    startButton?.isEnabled = false
                    editButton?.isEnabled = true
                }
                CaptureService.STATUS_STOPPED, CaptureService.STATUS_FAILED -> {
                    captureActive = false
                    editMode = false
                    startButton?.text = "START SCREEN CAPTURE"
                    startButton?.isEnabled = true
                    editButton?.isEnabled = false
                    refreshHistory()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 24)
        window.navigationBarColor = Color.rgb(8, 11, 24)
        store = ChestCountStore(this)
        try {
            if (showSavedCrashReport()) return
            registerStatusReceiver()
            projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            showHome()
        } catch (error: Throwable) {
            android.util.Log.e("ChestCount", "Home screen failed to initialize", error)
            showStartupError(error)
        }
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(CaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun showHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(26))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 12, 29), Color.rgb(19, 20, 48), Color.rgb(9, 15, 32)))
        }
        root.addView(TextView(this).apply {
            text = "CHEST COUNT"
            textSize = 23f
            letterSpacing = 0.1f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "FORTNITE SESSION COMPANION"
            textSize = 10f
            letterSpacing = 0.15f
            setTextColor(Color.rgb(190, 169, 255))
            setPadding(0, dp(5), 0, dp(20))
        })

        val countCard = card()
        countCard.addView(TextView(this).apply {
            text = "CURRENT SESSION"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(194, 178, 255))
        })
        val count = TextView(this).apply {
            text = store.currentCount().toString()
            textSize = 48f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        countView = count
        countCard.addView(count)
        countCard.addView(TextView(this).apply {
            text = "chests counted this run"
            textSize = 13f
            setTextColor(Color.rgb(185, 192, 218))
        })
        root.addView(countCard, LinearLayout.LayoutParams(-1, -2))

        root.addView(sectionLabel("SESSION CONTROLS"))
        val status = TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity))
                "Ready. Start screen capture when you are in Fortnite."
            else "Allow Display over other apps before starting."
            textSize = 13f
            setTextColor(Color.rgb(229, 231, 245))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.rgb(24, 29, 51), dp(13), Color.rgb(61, 66, 99))
        }
        statusView = status
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val overlayPermission = button("◉  FLOATING COUNTER ACCESS", false)
        overlayPermission.setOnClickListener { requestOverlayPermission() }
        root.addView(overlayPermission, buttonParams())

        val gameAccess = button("🎮  GAME-ONLY DETECTION ACCESS", false)
        gameAccess.setOnClickListener { requestUsagePermission() }
        root.addView(gameAccess, buttonParams())

        val start = button("▶  START CAPTURE", true)
        startButton = start
        start.setOnClickListener {
            if (!ForegroundGameGuard(this).hasUsageAccess()) {
                status.text = "Allow Usage Access so counting pauses outside Fortnite."
                requestUsagePermission()
            } else if (!Settings.canDrawOverlays(this)) {
                status.text = "Please enable Display over other apps, then return here."
                requestOverlayPermission()
            } else {
                try {
                    val manager = projectionManager
                        ?: getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    status.text = "Waiting for Android screen-capture approval..."
                    start.isEnabled = false
                    @Suppress("DEPRECATION")
                    startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
                } catch (error: Exception) {
                    start.isEnabled = true
                    status.text = "Could not request capture: ${error.javaClass.simpleName}"
                }
            }
        }
        root.addView(start, buttonParams(58))

        val move = button("✥  MOVE COUNTER", false)
        editButton = move
        move.isEnabled = false
        move.setOnClickListener {
            if (!captureActive) return@setOnClickListener
            editMode = !editMode
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_SET_EDIT_MODE
                putExtra(CaptureService.EXTRA_EDIT_MODE, editMode)
            })
            move.text = if (editMode) "FINISH POSITIONING" else "MOVE COUNTER"
        }
        root.addView(move, buttonParams())

        val stop = button("■  STOP CAPTURE", false)
        stop.setOnClickListener {
            status.text = "Stopping capture..."
            stopService(Intent(this, CaptureService::class.java))
        }
        root.addView(stop, buttonParams())

        root.addView(sectionLabel("RECENT SESSIONS"))
        val historyBox = card()
        historyContainer = historyBox
        root.addView(historyBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        refreshHistory(historyBox)

        root.addView(TextView(this).apply {
            text = "Detection is experimental and may miss or miscount chests. Review your gameplay to verify results. Sessions stay on this device."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(151, 161, 190))
            setPadding(dp(8), dp(18), dp(8), 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun refreshHistory(target: LinearLayout? = null) {
        val box = target ?: findHistoryBox() ?: return
        box.removeAllViews()
        val sessions = store.recentSessions()
        if (sessions.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "No completed sessions yet."
                textSize = 13f
                setTextColor(Color.rgb(185, 192, 218))
            })
            return
        }
        sessions.take(8).forEach { session ->
            val minutes = session.durationMs / 60000
            val seconds = (session.durationMs / 1000) % 60
            box.addView(TextView(this).apply {
                text = "${session.total} chests  •  ${minutes}m ${seconds}s\nDetected: ${session.detected}   Manual adjustment: ${session.manual}"
                textSize = 13f
                setTextColor(Color.rgb(230, 232, 247))
                setPadding(0, dp(7), 0, dp(7))
            })
        }
    }

    private fun findHistoryBox(): LinearLayout? = historyContainer

    private fun requestUsagePermission() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            statusView?.text = "Open Settings > Special access > Usage access > Chest Count."
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            statusView?.text = "Floating counter permission is already enabled."
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            statusView?.text = "Open Settings and allow Chest Count to display over other apps."
        }
    }

    @Deprecated("Uses platform activity result callback for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        startButton?.isEnabled = true
        if (resultCode != RESULT_OK || data == null) {
            statusView?.text = "Screen capture was cancelled. Nothing is recording."
            return
        }
        try {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            statusView?.text = "Starting capture. Waiting for Android..."
        } catch (error: Exception) {
            android.util.Log.e("ChestCount", "Could not start capture service", error)
            statusView?.text = "Capture could not start: ${error.javaClass.simpleName}"
        }
    }

    override fun onResume() {
        super.onResume()
        if (!captureActive) {
            statusView?.text = when {
                !ForegroundGameGuard(this).hasUsageAccess() ->
                    "Enable Game-only detection access before starting."
                !Settings.canDrawOverlays(this) ->
                    "Enable floating counter access before starting."
                else -> "Ready. Open Fortnite, then start capture."
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun showSavedCrashReport(): Boolean {
        val file = File(filesDir, ChestCountApplication.CRASH_REPORT_FILE)
        if (!file.exists()) return false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(10, 12, 25))
        }
        root.addView(TextView(this).apply {
            text = "STARTUP CRASH DETAILS"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = try { file.readText() } catch (e: Exception) { "Could not read crash report: ${e.message}" }
            textSize = 11f
            setTextColor(Color.rgb(255, 220, 150))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        })
        val clear = button("CLEAR REPORT AND TRY AGAIN", true)
        clear.setOnClickListener { file.delete(); recreate() }
        root.addView(clear, buttonParams())
        setContentView(ScrollView(this).apply { addView(root) })
        return true
    }

    private fun showStartupError(error: Throwable) {
        setContentView(TextView(this).apply {
            text = "Chest Count couldn't load.\n\n${error.javaClass.simpleName}: ${error.message ?: "No details"}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(Color.rgb(12, 14, 28))
        })
        Toast.makeText(this, "Chest Count startup error", Toast.LENGTH_LONG).show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Color.rgb(24, 29, 51), dp(16), Color.rgb(61, 66, 99))
    }

    private fun sectionLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.13f
        setTextColor(Color.rgb(194, 178, 255))
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(9))
    }

    private fun button(label: String, primary: Boolean) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        letterSpacing = 0.04f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = if (primary) rounded(Color.rgb(100, 67, 220), dp(14))
            else rounded(Color.rgb(36, 42, 67), dp(14), Color.rgb(79, 83, 124))
        stateListAnimator = null
    }

    private fun buttonParams(height: Int = 52) =
        LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(9) }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_CAPTURE = 4012 }
}
