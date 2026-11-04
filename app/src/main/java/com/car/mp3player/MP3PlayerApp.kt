package com.car.mp3player

import android.app.Application
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.util.CrashLogger

class MP3PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install first: anything after this point can be captured if it throws.
        CrashLogger.install(this)
        AppForegroundTracker.init(this)
        runCatching { SettingsRepository(this).applyTheme() }
    }
}
