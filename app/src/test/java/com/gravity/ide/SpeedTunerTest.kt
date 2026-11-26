package com.gravity.ide

import com.gravity.ide.ai.local.SpeedTuner
import com.gravity.ide.ai.local.SpeedTuner.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTunerTest {
    @Test fun triesTheUsualCountAndMoreCores() {
        // Poco X8 Pro Max (Dimensity 9500): 8 cores, 4 fast ones.
        assertEquals(listOf(4, 8, 6), SpeedTuner.readCandidates(4, 8))
        assertEquals(listOf(4, 6), SpeedTuner.writeCandidates(4, 8))
        // A 4-core phone: nothing more to try.
        assertEquals(listOf(4), SpeedTuner.readCandidates(4, 4))
        assertEquals(listOf(4), SpeedTuner.writeCandidates(4, 4))
        // More than 8 cores: at most 8.
        assertEquals(8, SpeedTuner.readCandidates(4, 12).max())
    }

    @Test fun keepsTheUsualSetupUnlessClearlyFaster() {
        val usual = Run(4, 4, 250, 10_000, 32, 4_000)       // 25 t/s reading
        val slightly = Run(4, 8, 250, 9_700, 32, 4_000)     // 25.8 t/s: noise
        val clearly = Run(4, 6, 250, 8_000, 32, 4_000)      // 31 t/s
        assertEquals(usual, SpeedTuner.pick(listOf(usual, slightly), 4) { it.readTps })
        assertEquals(clearly, SpeedTuner.pick(listOf(usual, slightly, clearly), 4) { it.readTps })
    }

    @Test fun testPromptsDifferSoNothingIsReused() {
        val a = SpeedTuner.testPrompt(1)
        val b = SpeedTuner.testPrompt(2)
        assertNotEquals(a.take(12), b.take(12))
        assertTrue(a.length in 600..2000)
    }
}
