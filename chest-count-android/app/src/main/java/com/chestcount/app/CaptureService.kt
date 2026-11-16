package com.chestcount.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var frameCount = 0
    private var stopping = false
    private var failureReported = false
    private var windowManager: WindowManager? = null
    private var counterView: View? = null
    private var counterNumber: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var editMode = false
    private lateinit var store: ChestCountStore
    private lateinit var gameGuard: ForegroundGameGuard
    private var lastGameForeground: Boolean? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val chestDetector = ChestOpeningDetector()

    override fun onCreate() {
        super.onCreate()
        store = ChestCountStore(this)
        gameGuard = ForegroundGameGuard(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_EDIT_MODE && projection != null) {
            setEditMode(intent.getBooleanExtra(EXTRA_EDIT_MODE, false))
            return START_NOT_STICKY
        }
        if (projection != null) {
            sendStatus(STATUS_RUNNING, "Capture is already running.")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null || resultCode != Activity.RESULT_OK) {
            reportFailure("Screen-capture approval was missing. Please start again from the app.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                throw IllegalStateException("Display-over-other-apps permission is required.")
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android did not provide a screen-capture session.")
            val activeProjection = projection!!
            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    sendStatus(STATUS_STOPPED, "Android stopped screen capture.")
                    stopCapture(stopProjection = false)
                    stopSelf()
                }
            }, Handler(mainLooper))

            store.beginSession()
            startCapture()
            if (showCounterOverlay()) {
                sendStatus(STATUS_RUNNING, "Capture is running. Switch to Fortnite.")
            } else {
                sendStatus(STATUS_RUNNING, "Capture is running, but the counter could not be displayed.")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture.", error)
            reportFailure("Capture failed: ${error.javaClass.simpleName}: ${error.message ?: "No details"}")
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun reportFailure(message: String) {
        failureReported = true
        sendStatus(STATUS_FAILED, message)
    }

    private fun sendStatus(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS_STATE, state)
            putExtra(EXTRA_STATUS_MESSAGE, message)
            putExtra(EXTRA_COUNT, if (::store.isInitialized) store.currentCount() else 0)
            putExtra(EXTRA_EDIT_MODE, editMode)
        })
    }

    private fun showCounterOverlay(): Boolean {
        if (counterView != null) return true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.rgb(18, 19, 34))
                setStroke(dp(1), Color.rgb(132, 82, 255))
            }
            elevation = dp(5).toFloat()
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_chest_count)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        pill.addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
        val number = TextView(this).apply {
            text = store.currentCount().toString()
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(7), 0, 0, 0)
            minWidth = dp(18)
        }
        pill.addView(number)
        counterNumber = number

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = store.overlayX()
            y = store.overlayY()
        }
        overlayParams = params
        pill.setOnTouchListener { view, event ->
            if (!editMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.setBackgroundColor(Color.TRANSPARENT)
                    dragStartX = params.x
                    dragStartY = params.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (dragStartX - (event.rawX - dragTouchX).toInt()).coerceAtLeast(0)
                    params.y = (dragStartY + (event.rawY - dragTouchY).toInt()).coerceAtLeast(0)
                    try { windowManager?.updateViewLayout(pill, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    store.saveOverlayPosition(params.x, params.y)
                    true
                }
                else -> true
            }
        }

        return try {
            windowManager?.addView(pill, params)
            counterView = pill
            setEditMode(false)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not show floating counter.", error)
            counterNumber = null
            overlayParams = null
            false
        }
    }

    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        val view = counterView ?: return
        val params = overlayParams ?: return
        params.flags = if (enabled) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // Android 12+ limits combined obscuring opacity for pass-through overlays.
        params.alpha = if (enabled) 1.0f else 0.78f
        try {
            windowManager?.updateViewLayout(view, params)
            view.alpha = if (enabled) 1.0f else 0.96f
            sendStatus(STATUS_RUNNING, if (enabled) {
                "Counter edit mode: drag it where you want, then turn edit mode off."
            } else {
                "Gameplay mode: counter is touch-through."
            })
        } catch (error: Exception) {
            Log.w(TAG, "Could not change counter interaction mode.", error)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)

        val frameWorker = HandlerThread("ChestCountFrames")
        frameWorker.start()
        worker = frameWorker
        val handler = Handler(frameWorker.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            var image: Image? = null
            try {
                image = source.acquireLatestImage()
                if (image != null) {
                    frameCount++
                    if (frameCount % 4 == 0) {
                        val gameForeground = gameGuard.isFortniteForeground()
                        if (lastGameForeground != gameForeground) {
                            lastGameForeground = gameForeground
                            if (!gameForeground) {
                                // Never carry a tracked chest or pending animation across apps.
                                chestDetector.reset()
                            }
                            mainHandler.post {
                                counterView?.visibility = if (gameForeground) View.VISIBLE else View.GONE
                                if (gameForeground) {
                                    sendStatus(STATUS_RUNNING, "Fortnite detected. Chest tracking is active.")
                                } else {
                                    sendStatus(STATUS_RUNNING, "Detection paused. Return to Fortnite to continue.")
                                }
                            }
                        }
                        if (gameForeground && chestDetector.process(image)) {
                            store.addDetected()
                            val count = store.currentCount()
                            mainHandler.post {
                                counterNumber?.text = count.toString()
                                sendStatus(STATUS_RUNNING, "Chest event detected.")
                            }
                            Log.i(TAG, "Experimental chest event. Count: $count")
                        } else if (!gameForeground) {
                            // Keep image history fresh only after a full reset when Fortnite returns.
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Screen-frame processing failed.", error)
            } finally {
                image?.close()
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "ChestCountCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        ) ?: throw IllegalStateException("Android did not create the virtual display.")
        Log.i(TAG, "Screen capture started at ${width}x${height}.")
    }

    private fun stopCapture(stopProjection: Boolean = true) {
        if (stopping) return
        stopping = true
        try {
            counterView?.let { view ->
                try { windowManager?.removeView(view) } catch (error: Exception) {
                    Log.w(TAG, "Counter overlay was already removed.", error)
                }
            }
            counterView = null
            counterNumber = null
            overlayParams = null
            windowManager = null
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            worker?.quitSafely()
            worker = null
            if (::store.isInitialized) store.finishSession()
            val activeProjection = projection
            projection = null
            if (stopProjection) activeProjection?.stop()
        } finally {
            stopping = false
        }
    }

    override fun onDestroy() {
        stopCapture()
        if (!failureReported) sendStatus(STATUS_STOPPED, "Capture stopped.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Chest Count screen capture",
                NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("Chest Count is running")
            .setContentText("The floating counter is active over your game.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STATUS = "com.chestcount.app.CAPTURE_STATUS"
        const val ACTION_SET_EDIT_MODE = "com.chestcount.app.SET_EDIT_MODE"
        const val EXTRA_EDIT_MODE = "edit_mode"
        const val EXTRA_COUNT = "count"
        const val EXTRA_STATUS_STATE = "status_state"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_FAILED = "failed"
        private const val CHANNEL_ID = "chest_count_capture"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ChestCount"
    }
}
