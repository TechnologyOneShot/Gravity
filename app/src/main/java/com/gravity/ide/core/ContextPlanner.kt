package com.gravity.ide.core

/**
 * Picks a context size for one on-device request. A bigger context lets a small model write a
 * longer answer (e.g. a whole app in one file), but costs memory while it generates, so it is
 * only used when the estimate fits comfortably in the RAM that is free right now.
 */
object ContextPlanner {
    /** Rough bytes per context token: f16 K and V cache plus attention scratch for a 512-token batch. */
    fun bytesPerToken(nLayer: Int, nEmbd: Int, nHead: Int, nHeadKv: Int): Long {
        if (nLayer <= 0 || nEmbd <= 0 || nHead <= 0) return 160L * 1024
        val kvDims = nEmbd.toLong() / nHead * (if (nHeadKv > 0) nHeadKv else nHead)
        return nLayer * 2L * kvDims * 2L + 512L * nHead * 4L
    }

    /**
     * The largest of [wanted] and a few smaller steps that is above [loaded], within the model's
     * training context [trainCtx] (0 = unknown) and whose estimated memory (with a 30% margin) is
     * at most half of [availableBytes]. Falls back to [loaded].
     */
    fun choose(loaded: Int, wanted: Int, trainCtx: Int, bytesPerToken: Long, availableBytes: Long): Int {
        val cap = if (trainCtx > 0) minOf(wanted, trainCtx) else wanted
        val steps = listOf(cap, cap * 3 / 4, cap / 2).filter { it > loaded }.distinct()
        for (ctx in steps) {
            val need = ctx.toLong() * bytesPerToken * 13 / 10
            if (need <= availableBytes / 2) return ctx
        }
        return loaded
    }
}
