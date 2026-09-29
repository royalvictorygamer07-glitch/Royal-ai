package com.example

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.io.File

class JarvisApp : Application() {

    init {
        try {
            android.system.Os.setenv("MESA_LOG_FILE", "/dev/null", true)
        } catch (_: Throwable) {}
    }

    override fun attachBaseContext(base: Context?) {
        try {
            android.system.Os.setenv("MESA_LOG_FILE", "/dev/null", true)
        } catch (_: Throwable) {}
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        try {
            android.system.Os.setenv("MESA_LOG_FILE", "/dev/null", true)
        } catch (_: Throwable) {}
        super.onCreate()

        try {
            File(cacheDir, "WebView").deleteRecursively()
        } catch (_: Throwable) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            if (packageName != processName) {
                WebView.setDataDirectorySuffix(processName)
            }
        }
    }
}
