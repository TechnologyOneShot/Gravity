package com.gravity.ide

import com.gravity.ide.core.GgufReader
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufEstimateTest {
    private fun info(layers: Int, embd: Int, heads: Int, kvHeads: Int) =
        GgufReader.Info(3, 0, "qwen2", null, 15, "Q4_K_M", 32768, null, null, true, 0, layers, embd, heads, kvHeads)

    @Test fun qwen7bFitsAPhoneWith12GbAt8192() {
        // A user's Poco X8 Pro Max (11 GB usable): the limit shown was 6.10 GB, and the old guess of
        // 160 KiB a token made Qwen2.5 7B Q4_K_M (4.36 GB) "too big" at an 8192 context.
        val file = (4.36 * (1L shl 30)).toLong()
        val limit = (11L shl 30) * 6 / 10 - (512L shl 20)
        val qwen7b = info(28, 3584, 28, 4)
        val est = GgufReader.estimateRamBytes(file, 8192, qwen7b)
        assertTrue("estimate ${est / (1 shl 20)} MB", est < limit)
        assertTrue(est < GgufReader.estimateRamBytes(file, 8192))
        // At 32768 it really does not fit comfortably.
        assertTrue(GgufReader.estimateRamBytes(file, 32768, qwen7b) > limit)
        // Without the header, the old guess.
        assertTrue(GgufReader.estimateRamBytes(file, 8192, null) == GgufReader.estimateRamBytes(file, 8192))
    }
}
