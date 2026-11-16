package com.chestcount.app

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/** Persists uncaught startup errors for display on the next launch. */
class ChestCountApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { writer ->
                    error.printStackTrace(PrintWriter(writer))
                }.toString()
                val report = buildString {
                    appendLine("Chest Count crash report")
                    appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(trace)
                }
                openFileOutput(CRASH_REPORT_FILE, MODE_PRIVATE).use {
                    it.write(report.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                Log.e(TAG, "Uncaught application exception saved for next launch.", error)
            } catch (reportError: Throwable) {
                Log.e(TAG, "Could not save crash report.", reportError)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }
    companion object {
        const val CRASH_REPORT_FILE = "last_crash_report.txt"
        private const val TAG = "ChestCountCrash"
    }
}