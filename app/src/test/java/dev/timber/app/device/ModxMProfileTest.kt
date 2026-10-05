package dev.timber.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModxMProfileTest {
    @Test
    fun fullLayoutAt44100() {
        val layout = ModxMProfile.resolveLayout(10, 4, 44_100)
        assertEquals(10, layout.inputChannelCount)
        assertEquals("Main L", layout.inputLabels[0])
        assertEquals("USB 8", layout.inputLabels[9])
        assertEquals(4, layout.stereoPairs.size)
        assertTrue(layout.notes.contains("Full MODX M"))
    }

    @Test
    fun stereoClassCompliantFallback() {
        val layout = ModxMProfile.resolveLayout(2, 2, 44_100)
        assertEquals(listOf("Main L", "Main R"), layout.inputLabels)
        assertTrue(layout.notes.contains("stereo"))
    }
}
