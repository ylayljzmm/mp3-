package com.suisheng.player

import android.app.Application
import android.content.Intent
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.util.CrashLogger

class SuishengApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install first: anything after this point can be captured if it throws.
        CrashLogger.install(this)
        AppForegroundTracker.init(this)
        runCatching { SettingsRepository(this).applyTheme() }

        // 5.7.1（需求 6）：在进程启动阶段就把播放服务与媒体会话拉起来，
        // 不再等「打开 Activity → 全库扫描结束」—— 那样未进 App 时方向盘 /
        // 蓝牙 / 语音没有任何可控制的会话。用普通 startService 而不是
        // startForegroundService：预热动作不进前台、不弹通知、不播放，
        // 也就不会撞上 Android 12+ 的后台 FGS 启动限制。
        // runCatching 兜底：某些 ROM 在进程刚创建时仍会拒绝后台 startService，
        // 失败就退回旧行为（用户点开 App 后再恢复），不影响启动。
        runCatching {
            startService(
                Intent(this, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_ENSURE_SESSION
                }
            )
        }
    }
}
