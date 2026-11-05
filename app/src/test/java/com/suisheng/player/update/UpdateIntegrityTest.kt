package com.suisheng.player.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateIntegrityTest {
    @Test
    fun `calculates a lowercase sha256 digest`() {
        val file = File.createTempFile("mp3-update-hash", ".txt")
        try {
            file.writeText("abc", Charsets.UTF_8)
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateIntegrity.sha256(file),
            )
        } finally {
            file.delete()
        }
    }
}
