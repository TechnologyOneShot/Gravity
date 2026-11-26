package com.gravity.ide

import com.gravity.ide.core.ReplySpeed
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplySpeedTest {
    @Test fun aSlowDrawingReadsPlainly() {
        assertEquals(
            "Read 660 tokens in 48.2 s (390 already read) · wrote 620 in 8 min 15 s (1.3 a second) · 2 threads, fast engine",
            ReplySpeed.line(1050, 390, 48_200, 620, 495_000, 2, "FAST"),
        )
    }

    @Test fun aQuickReply() {
        assertEquals(
            "Read 12 tokens in 850 ms (400 already read) · wrote 40 in 4.0 s (10.0 a second) · 4 threads, standard engine",
            ReplySpeed.line(412, 400, 850, 40, 4_000, 4, "BASE"),
        )
    }
}
