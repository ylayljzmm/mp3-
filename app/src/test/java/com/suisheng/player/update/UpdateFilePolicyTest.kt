package com.suisheng.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateFilePolicyTest {
    @Test
    fun `keeps a normal semantic version readable`() {
        assertEquals("mp3-player-4.1.0.apk", UpdateFilePolicy.apkFileName("4.1.0"))
    }

    @Test
    fun `removes path separators from an untrusted server version`() {
        val name = UpdateFilePolicy.apkFileName("../../4.2\\preview")

        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertEquals("mp3-player-.._.._4.2_preview.apk", name)
    }

    @Test
    fun `uses a stable fallback for a blank version`() {
        assertEquals("mp3-player-update.apk", UpdateFilePolicy.apkFileName("  "))
    }
}
