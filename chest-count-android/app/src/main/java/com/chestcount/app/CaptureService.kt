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
    private var isStopping = false
    private var failureReported = false
    private var windowManager: WindowManager? = null
    private var counterView: View? = null
    private var chestCount = 0
    private var counterNumber: TextView? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val chestDetector = ChestOpeningDetector()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) {
            sendStatus(STATUS_RUNNING, "Capture is already running.")
            return START_NOT_STICKY
        }

        // Validate the one-time consent result before promoting this service.
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
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            if (!android.provider.Settings.canDrawOverlays(this)) {
                throw IllegalStateException("Display-over-other-apps permission is required.")
            }

            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val activeProjection = projection
                ?: throw IllegalStateException("Android did not provide a screen-capture session.")

            activeProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    sendStatus(STATUS_STOPPED, "Android stopped screen capture.")
                    stopCapture(stopProjection = false)
                    stopSelf()
                }
            }, Handler(mainLooper))

            startCapture()
            val overlayVisible = showCounterOverlay()
            if (overlayVisible) {
                sendStatus(STATUS_RUNNING, "Screen capture is running. Switch to Fortnite.")
            } else {
                sendStatus(STATUS_RUNNING, "Capture is running, but the floating counter could not be displayed.")
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
        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_STATE, state)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(statusIntent)
    }

    private fun showCounterOverlay(): Boolean {
        if (counterView != null) return true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(17), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32).toFloat()
                setColor(Color.argb(242, 13, 14, 27))
                setStroke(dp(2), Color.rgb(132, 82, 255))
            }
            elevation = dp(8).toFloat()
        }

        val chestIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_chest_count)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        pill.addView(chestIcon, LinearLayout.LayoutParams(dp(44), dp(44)))

        val number = TextView(this).apply {
            text = chestCount.toString()
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(13), 0, 0, 0)
            minWidth = dp(25)
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
            x = dp(12)
            y = dp(82)
        }

        pill.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX - (event.rawX - touchX).toInt()).coerceAtLeast(0)
                        params.y = (startY + (event.rawY - touchY).toInt()).coerceAtLeast(0)
                        windowManager?.updateViewLayout(pill, params)
                        return true
                    }
                }
                return true
            }
        })

        return try {
            windowManager?.addView(pill, params)
            counterView = pill
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not show floating chest counter.", error)
            counterNumber = null
            false
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
            var image: android.media.Image? = null
            try {
                image = source.acquireLatestImage()
                if (image != null) {
                    frameCount++
                    if (frameCount % 4 == 0 && chestDetector.process(image)) {
                        chestCount++
                        mainHandler.post { counterNumber?.text = chestCount.toString() }
                        Log.i(TAG, "Chest event detected. Count: $chestCount")
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Error while acquiring a screen frame.", error)
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
        if (isStopping) return
        isStopping = true
        try {
            counterView?.let { view ->
                try {
                    windowManager?.removeView(view)
                } catch (error: Exception) {
                    Log.w(TAG, "Floating counter was already removed.", error)
                }
            }
            counterView = null
            counterNumber = null
            windowManager = null

            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null

            worker?.quitSafely()
            worker = null

            val activeProjection = projection
            projection = null
            if (stopProjection) activeProjection?.stop()
        } finally {
            isStopping = false
        }
    }

    override fun onDestroy() {
        stopCapture()
        if (!failureReported) {
            sendStatus(STATUS_STOPPED, "Capture stopped.")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Chest Count screen capture", NotificationManager.IMPORTANCE_LOW
            )
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
        return builder
            .setContentTitle("Chest Count is running")
            .setContentText("The floating chest counter is active over your game.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STATUS = "com.chestcount.app.CAPTURE_STATUS"
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
