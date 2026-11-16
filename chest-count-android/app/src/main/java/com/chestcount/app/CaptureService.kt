package com.chestcount.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
    private var windowManager: WindowManager? = null
    private var counterView: View? = null
    private var counterParams: WindowManager.LayoutParams? = null
    private var chestCount = 0
    private var counterNumber: TextView? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val chestDetector = ChestOpeningDetector()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) {
            Log.w(TAG, "Capture is already running; ignoring duplicate start.")
            return START_NOT_STICKY
        }

        createNotificationChannel()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(this)
            ) {
                throw IllegalStateException("Display-over-other-apps permission is required.")
            }
            showCounterOverlay()

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
                Log.e(TAG, "Missing or invalid screen-capture permission result.")
                stopSelf(startId)
                return START_NOT_STICKY
            }

            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)

            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection was stopped by the system or user.")
                    stopCapture(stopProjection = false)
                    stopSelf()
                }
            }, Handler(mainLooper))

            startCapture()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture.", error)
            stopCapture()
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun showCounterOverlay() {
        if (counterView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(205, 13, 17, 32))
                setStroke(dp(1), Color.rgb(125, 70, 255))
            }
            elevation = dp(5).toFloat()
        }

        val chestIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_chest_count)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        pill.addView(chestIcon, LinearLayout.LayoutParams(dp(27), dp(27)))

        val number = TextView(this).apply {
            text = chestCount.toString()
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, 0, 0)
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
            y = dp(110)
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

        try {
            windowManager?.addView(pill, params)
            counterView = pill
            counterParams = params
        } catch (error: Exception) {
            Log.e(TAG, "Could not show floating chest counter.", error)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        val frameWorker = HandlerThread("ChestCountFrames")
        frameWorker.start()
        worker = frameWorker
        val handler = Handler(frameWorker.looper)

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ source ->
            var image: android.media.Image? = null
            try {
                image = source.acquireLatestImage()
                if (image != null) {
                    frameCount++
                    // Analyse a few frames per second to keep gameplay overhead low.
                    if (frameCount % 6 == 0 && chestDetector.process(image)) {
                        chestCount++
                        mainHandler.post { counterNumber?.text = chestCount.toString() }
                        Log.i(TAG, "Possible chest opening detected. Count: $chestCount")
                    }
                    if (frameCount % 180 == 0) {
                        Log.d(TAG, "Captured $frameCount frames")
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Error while acquiring a screen frame.", error)
            } finally {
                image?.close()
            }
        }, handler)

        val display = projection?.createVirtualDisplay(
            "ChestCountCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        if (display == null) {
            throw IllegalStateException("Android did not create the virtual display.")
        }
        virtualDisplay = display
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
            counterParams = null
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
            if (stopProjection) {
                activeProjection?.stop()
            }
        } finally {
            isStopping = false
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chest Count screen capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
            .setContentTitle("Chest Count is capturing")
            .setContentText("Floating counter is visible. Chest detection is still being developed.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "chest_count_capture"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ChestCount"
    }
}
