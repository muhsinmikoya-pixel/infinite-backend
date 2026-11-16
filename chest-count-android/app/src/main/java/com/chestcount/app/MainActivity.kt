package com.chestcount.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var status: TextView

    private val captureRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                status.text = "Capture started. The chest icon and count 0 are shown in the small floating pill."
            } else {
                status.text = "Screen capture permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val title = TextView(this).apply {
            text = "CHEST COUNT"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Small floating chest counter. Detection is being built."
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val overlayPermission = Button(this).apply {
            text = "Enable floating counter"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    status.text = "Floating counter permission is enabled."
                } else {
                    val permissionIntent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(permissionIntent)
                }
            }
        }
        val start = Button(this).apply {
            text = "Start screen capture"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    status.text = "First enable 'Display over other apps' for Chest Count."
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    status.text = "Waiting for Android screen-capture permission..."
                    captureRequest.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
        val stop = Button(this).apply {
            text = "Stop capture and counter"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "Capture and floating counter stopped."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 48, 28, 48)
            addView(title)
            addView(status)
            addView(overlayPermission)
            addView(start)
            addView(stop)
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && Settings.canDrawOverlays(this)) {
            status.text = "Floating counter permission is enabled. Start capture when ready."
        }
    }
}