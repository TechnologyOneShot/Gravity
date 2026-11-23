package com.gravity.ide.device

import com.gravity.ide.data.settings.AppSettings

/**
 * Turns the performance settings into concrete resource limits. Values are applied when a
 * local model is loaded and when agent tasks are scheduled; nothing here is a benchmark claim.
 */
object PerformanceManager {

    fun threads(settings: AppSettings, info: DeviceInfo): Int {
        val cores = info.cpuCores.coerceAtLeast(1)
        val base = when (settings.performanceMode) {
            "HIGH" -> cores
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
