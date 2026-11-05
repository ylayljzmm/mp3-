package com.suisheng.player.update

internal object UpdateFilePolicy {
    private val unsafeFileCharacter = Regex("[^0-9A-Za-z._-]")

    fun apkFileName(version: String): String {
        val safeVersion = version.trim()
            .replace(unsafeFileCharacter, "_")
            .take(48)
            .ifBlank { "update" }
        return "mp3-player-$safeVersion.apk"
    }
}
