package com.gravity.ide

import com.gravity.ide.device.PerformanceManager
import org.junit.Assert.assertEquals
import org.junit.Test

class FastCoresTest {
    @Test fun onlyTheFastCoresAreCounted() {
        // 1 prime + 3 big + 4 little (Snapdragon 8 Gen 2 style): 4 fast cores.
        assertEquals(4, PerformanceManager.fastCores(listOf(1_804_800L, 1_804_800, 1_804_800, 1_804_800, 2_803_200, 2_803_200, 2_803_200, 3_187_200)))
        // 2 big + 6 little (many mid-range phones): 2.
        assertEquals(2, PerformanceManager.fastCores(listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_600_000, 2_600_000)))
        // All cores alike: all of them.
        assertEquals(8, PerformanceManager.fastCores(List(8) { 2_400_000L }))
        // Unknown (no cpufreq): 0, so a fixed default is used.
        assertEquals(0, PerformanceManager.fastCores(List(8) { 0L }))
    }
}
