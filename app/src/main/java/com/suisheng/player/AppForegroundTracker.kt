package com.suisheng.player

import android.app.Activity
import android.app.Application
import android.os.Bundle

object AppForegroundTracker {
    private var startedActivityCount = 0
    var isInForeground: Boolean = false
        private set

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityStarted(activity: Activity) {
                if (startedActivityCount++ == 0) {
                    setForeground(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivityCount <= 0) {
                    startedActivityCount = 0
                    setForeground(false)
                }
            }
        })
    }

    private fun setForeground(foreground: Boolean) {
        if (isInForeground == foreground) return
        isInForeground = foreground
        LyricsOverlayService.onAppForegroundChanged(foreground)
    }
}
