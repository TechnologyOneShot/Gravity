package com.gravity.ide.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Joins WAV files with the same format into one (text-to-speech writes long text in parts). Pure Kotlin, unit tested. */
object WavJoin {
    private class Wav(val fmt: ByteArray, val data: ByteArray)

    private fun parse(bytes: ByteArray): Wav {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var fmt: ByteArray? = null
        var data: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val declared = b.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val start = pos + 8
            // Some writers leave the data size at 0 or too big while streaming: take what is there.
            val size = if (declared == 0L || start + declared > bytes.size) (bytes.size - start).toLong() else declared
            when (id) {
                "fmt " -> fmt = bytes.copyOfRange(start, start + size.toInt())
                "data" -> data = bytes.copyOfRange(start, start + size.toInt())
            }
            pos = start + size.toInt() + (size.toInt() and 1)
        }
        return Wav(fmt ?: throw IllegalArgumentException("WAV without a format chunk"), data ?: ByteArray(0))
    }

    fun join(parts: List<ByteArray>): ByteArray {
        require(parts.isNotEmpty()) { "Nothing to join" }
        val wavs = parts.map(::parse)
        val fmt = wavs.first().fmt
        require(wavs.all { it.fmt.contentEquals(fmt) }) { "The parts have different audio formats" }
        val dataSize = wavs.sumOf { it.data.size }
        val out = ByteArrayOutputStream(44 + dataSize)
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); int(4 + 8 + fmt.size + 8 + dataSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); int(fmt.size); out.write(fmt)
        out.write("data".toByteArray(Charsets.US_ASCII)); int(dataSize)
        wavs.forEach { out.write(it.data) }
        return out.toByteArray()
    }

    /** A 16-bit mono PCM WAV of [samples] at [rate] Hz. */
    fun wavMono16(samples: ShortArray, rate: Int): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).array()
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); int(4 + 8 + 16 + 8 + samples.size * 2)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); int(16); out.write(fmt)
        out.write("data".toByteArray(Charsets.US_ASCII)); int(samples.size * 2)
        out.write(data.array())
        return out.toByteArray()
    }

    /** 16-bit PCM samples of a WAV (channels mixed to mono) and its sample rate. */
    fun pcmMono16(bytes: ByteArray): Pair<ShortArray, Int> {
        val w = parse(bytes)
        val f = ByteBuffer.wrap(w.fmt).order(ByteOrder.LITTLE_ENDIAN)
        val format = f.getShort(0).toInt()
        val channels = f.getShort(2).toInt().coerceAtLeast(1)
        val rate = f.getInt(4)
        val bits = f.getShort(14).toInt()
        require(format == 1 && bits == 16) { "Only 16-bit PCM WAV is supported (format $format, $bits bits)" }
        val d = ByteBuffer.wrap(w.data).order(ByteOrder.LITTLE_ENDIAN)
        val frames = w.data.size / (2 * channels)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += d.getShort((i * channels + c) * 2)
            out[i] = (sum / channels).toShort()
        }
        return out to rate
    }

    /** [samples] at [from] Hz resampled to [to] Hz (linear). */
    fun resample(samples: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || samples.isEmpty()) return samples
        val n = (samples.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to
            val a = pos.toInt().coerceAtMost(samples.lastIndex)
            val b = (a + 1).coerceAtMost(samples.lastIndex)
            val t = pos - a
            (samples[a] * (1 - t) + samples[b] * t).toInt().toShort()
        }
    }

    /** A PCM WAV file (for tests): [samples] 16-bit mono at [rate] Hz. */
    fun pcm16(samples: ShortArray, rate: Int = 16000): ByteArray {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).array()
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putShort(it) } }.array()
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); int(4 + 8 + 16 + 8 + data.size); out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); int(16); out.write(fmt)
        out.write("data".toByteArray(Charsets.US_ASCII)); int(data.size); out.write(data)
        return out.toByteArray()
    }
}
