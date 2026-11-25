package com.gravity.ide.core

/** Timing and pixel maths for videos made on the phone (pure Kotlin, unit tested). */
object VideoPlan {
    /** Seconds each scene is shown: its narration plus a short pause, at least [minSec]. */
    fun durations(narrationSamples: List<Int>, rate: Int, minSec: Double = 2.5, padSec: Double = 0.5): List<Double> =
        narrationSamples.map { n -> maxOf(minSec, (if (rate > 0) n.toDouble() / rate else 0.0) + padSec) }

    /**
     * ARGB pixels to YUV 4:2:0 (BT.601, video range): full-size [y], quarter-size [u] and [v]
     * (each chroma value from the top-left pixel of its 2x2 block).
     */
    fun argbToYuv420(argb: IntArray, w: Int, h: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        val cw = (w + 1) / 2
        for (j in 0 until h) {
            val row = j * w
            for (i in 0 until w) {
                val p = argb[row + i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                y[row + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val c = (j / 2) * cw + i / 2
                    u[c] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    v[c] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
    }
}
