package com.suisheng.player

import android.app.Application
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.util.CrashLogger

class MP3PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install first: anything after this point can be captured if it throws.
        CrashLogger.install(this)
        AppForegroundTracker.init(this)
        runCatching { SettingsRepository(this).applyTheme() }
    }
}
