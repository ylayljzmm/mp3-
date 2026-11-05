package com.suisheng.player.playback

/**
 * Decides when the playback service should reclaim the car's media-button route.
 *
 * Some head units silently move media-key ownership to another component while
 * audio keeps playing. Reasserting at a low frequency is cheap and makes the
 * route self-healing without touching playback state.
 */
class MediaControlRecoveryPolicy(
    private val recoveryIntervalMs: Long = DEFAULT_RECOVERY_INTERVAL_MS,
) {
    private var lastRecoveryAtMs: Long = Long.MIN_VALUE

    fun shouldRecover(nowMs: Long, isPlaying: Boolean, sessionReady: Boolean): Boolean {
        if (!isPlaying) return false
        val intervalElapsed = lastRecoveryAtMs == Long.MIN_VALUE ||
            nowMs - lastRecoveryAtMs >= recoveryIntervalMs
        if (!sessionReady || intervalElapsed) {
            lastRecoveryAtMs = nowMs
            return true
        }
        return false
    }

    fun reset() {
        lastRecoveryAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_RECOVERY_INTERVAL_MS = 15_000L
    }
}
