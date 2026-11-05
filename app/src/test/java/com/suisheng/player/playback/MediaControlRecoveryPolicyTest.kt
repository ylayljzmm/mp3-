package com.suisheng.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlRecoveryPolicyTest {
    @Test
    fun pausedPlaybackDoesNotClaimMediaRoute() {
        val policy = MediaControlRecoveryPolicy(1_000L)

        assertFalse(policy.shouldRecover(nowMs = 0L, isPlaying = false, sessionReady = false))
    }

    @Test
    fun missingSessionRecoversImmediatelyWhilePlaying() {
        val policy = MediaControlRecoveryPolicy(1_000L)

        assertTrue(policy.shouldRecover(nowMs = 10L, isPlaying = true, sessionReady = false))
    }

    @Test
    fun healthySessionIsRefreshedOnlyAfterInterval() {
        val policy = MediaControlRecoveryPolicy(1_000L)

        assertTrue(policy.shouldRecover(nowMs = 100L, isPlaying = true, sessionReady = true))
        assertFalse(policy.shouldRecover(nowMs = 1_099L, isPlaying = true, sessionReady = true))
        assertTrue(policy.shouldRecover(nowMs = 1_100L, isPlaying = true, sessionReady = true))
    }

    @Test
    fun resetForcesNextPlayingTickToRecover() {
        val policy = MediaControlRecoveryPolicy(10_000L)
        assertTrue(policy.shouldRecover(nowMs = 100L, isPlaying = true, sessionReady = true))

        policy.reset()

        assertTrue(policy.shouldRecover(nowMs = 101L, isPlaying = true, sessionReady = true))
    }
}
