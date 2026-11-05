package com.suisheng.player.util

object TimeFormat {
    fun mmss(durationMs: Long): String {
        if (durationMs <= 0L) return "--:--"
        val totalSec = (durationMs / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }
}
