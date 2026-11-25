package com.gravity.ide.device

import com.gravity.ide.data.settings.AppSettings

/**
 * Turns the performance settings into concrete resource limits. Values are applied when a
 * local model is loaded and when agent tasks are scheduled; nothing here is a benchmark claim.
 */
object PerformanceManager {

    /**
     * The phone's fast cores, from each core's maximum frequency: those within 80% of the fastest
     * (1+3+4 → 4, 2+6 → 2, 4+4 → 4). llama.cpp splits work evenly between its threads, so a thread
     * on a slow "little" core holds the fast ones back: fast cores only is quickest.
     */
    fun fastCores(maxFreqsKhz: List<Long>): Int {
        val known = maxFreqsKhz.filter { it > 0 }
        if (known.isEmpty()) return 0
        val top = known.max()
        return known.count { it * 10 >= top * 8 }.coerceAtLeast(1)
    }

    fun threads(settings: AppSettings, info: DeviceInfo): Int {
        val cores = info.cpuCores.coerceAtLeast(1)
        // Fast cores, at least 2 (a phone with one prime core still gains from a second thread), else 4.
        val fast = info.fastCores.takeIf { it > 0 }?.let { maxOf(it, 2) } ?: minOf(4, cores)
        val base = when (settings.performanceMode) {
            "AUTO" -> fast
            "HIGH" -> fast
            "LOW" -> 2
            else -> settings.localThreads
        }
        val thermalCap = when (info.thermalStatus) {
            "Severe", "Critical", "Emergency", "Shutdown" -> 2
            "Moderate" -> (cores / 2).coerceAtLeast(2)
            else -> cores
        }
        val lowMem = if (settings.lowMemoryMode || info.lowRam) 2 else cores
        return minOf(base, thermalCap, lowMem, cores).coerceAtLeast(1)
    }

    fun contextSize(settings: AppSettings, info: DeviceInfo): Int {
        val ctx = settings.localContextSize
        return if (settings.lowMemoryMode || info.lowRam || info.totalRamBytes < 4L * 1024 * 1024 * 1024) minOf(ctx, 2048) else ctx
    }

    fun maxParallelTasks(settings: AppSettings): Int = if (settings.lowMemoryMode) 1 else settings.maxParallelTasks
}
