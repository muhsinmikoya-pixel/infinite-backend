package com.chestcount.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
import android.view.WindowManager

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var frameCount = 0
    private var isStopping = false

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
                    if (frameCount % 60 == 0) {
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
            .setContentText("Screen frames are processed on this device. Stop capture in the app.")
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
