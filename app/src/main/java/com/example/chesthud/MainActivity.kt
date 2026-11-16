package com.example.chesthud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.rgb(13, 23, 43))
        }
        root.addView(TextView(this).apply {
            text = "CHEST HUD"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "A tiny Fortnite-inspired chest counter that floats over your games."
            textSize = 16f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
            setPadding(0, 18, 0, 24)
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener { openOverlaySettings() }
        })
        root.addView(Button(this).apply {
            text = "Show floating counter"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(Intent(this@MainActivity, ChestOverlayService::class.java))
                    finish()
                } else openOverlaySettings()
            }
        })
        root.addView(TextView(this).apply {
            text = "Allow permission, return here, then tap Show floating counter. Drag the top bar to move it."
            textSize = 13f; setTextColor(Color.rgb(180, 198, 225)); gravity = Gravity.CENTER
            setPadding(0, 22, 0, 0)
        })
        setContentView(root)
    }
    private fun openOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
