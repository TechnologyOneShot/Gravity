package com.gravity.ide.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.gravity.ide.core.VideoPlan
import com.gravity.ide.core.WavJoin
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Makes a real MP4 video on the phone from scenes the AI wrote: each scene's drawing (SVG) is shown
 * with a slow zoom and its narration as a caption, and the narration is read aloud by the phone's
 * voice. H.264 video + AAC audio with Android's own encoders; free, offline, no limits.
 *
 * It is a picture-by-picture video (like a slideshow), not AI-generated motion: that needs a video
 * model such as Google's Veo, which is only on paid plans.
 */
object VideoMaker {
    const val W = 720
    const val H = 1280
    private const val FPS = 24
    private const val TIMEOUT_US = 10_000L

    class Result(val file: File, val seconds: Double, val scenes: Int, val withVoice: Boolean)

    /** A video from scenes the AI drew: (narration, SVG drawing) pairs. */
    suspend fun make(
        context: Context, name: String, scenes: List<Pair<String, String>>, locale: Locale,
        progress: (String) -> Unit = {},
    ): Result {
        require(scenes.isNotEmpty()) { "No scenes to put in the video" }
        progress("Drawing ${scenes.size} scene(s)…")
        val pictures = scenes.map { (_, svg) ->
            runCatching { FileMaker.renderSvg(svg, W, H) }.getOrElse { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) } }
        }
        return makeFromPictures(context, name, scenes.map { it.first }, pictures, locale, progress)
    }

    /** A video from ready pictures ([W]x[H], e.g. from the online image AI), one per narration. They are recycled. */
    suspend fun makeFromPictures(
        context: Context, name: String, narrations: List<String>, pictures: List<Bitmap>, locale: Locale,
        progress: (String) -> Unit = {},
    ): Result {
        require(pictures.isNotEmpty() && pictures.size == narrations.size) { "No scenes to put in the video" }
        // Narration for each scene (the phone's voice); without text-to-speech the video is silent.
        progress("Recording the narration…")
        val clips: List<Pair<ShortArray, Int>?> = runCatching { FileMaker.speechClips(context, narrations, locale) }
            .getOrElse { List(narrations.size) { null } }
            .map { wav -> wav?.let { runCatching { WavJoin.pcmMono16(it) }.getOrNull() } }
        val rate = clips.firstOrNull { it != null }?.second ?: 0
        val voices = clips.map { c -> if (c == null || rate == 0) ShortArray(0) else WavJoin.resample(c.first, c.second, rate) }
        val seconds = VideoPlan.durations(voices.map { it.size }, rate)
        val scenes = narrations.map { it to "" }

        val out = FileMaker.unique(FileMaker.dir(context), FileMaker.clean(name) + ".mp4")
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            // 3. Audio first (kept in memory: short), so both tracks are known before the muxer starts.
            val audio = if (rate > 0) encodeAudio(voices, seconds, rate) else null
            progress("Making the video…")
            encodeVideo(pictures, scenes.map { it.first }, seconds, muxer, audio, progress)
        } catch (e: Exception) {
            runCatching { muxer.release() }
            out.delete()
            throw e
        } finally {
            pictures.forEach { it.recycle() }
        }
        runCatching { muxer.stop() }
        muxer.release()
        return Result(out, seconds.sum(), scenes.size, rate > 0)
    }

    private class Encoded(val format: MediaFormat, val samples: List<Pair<ByteBuffer, MediaCodec.BufferInfo>>)

    /** AAC of the narration, each scene's voice followed by silence up to the scene's length. */
    private fun encodeAudio(voices: List<ShortArray>, seconds: List<Double>, rate: Int): Encoded {
        val total = seconds.sumOf { (it * rate).toInt() }
        val pcm = ShortArray(total)
        var at = 0
        for ((i, v) in voices.withIndex()) {
            System.arraycopy(v, 0, pcm, at, minOf(v.size, total - at))
            at += (seconds[i] * rate).toInt()
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        var fed = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        buf.clear()
                        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN) // 16-bit PCM is little-endian
                        val n = minOf(buf.capacity() / 2, pcm.size - fed, 4096)
                        val time = fed * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (k in 0 until n) buf.putShort(pcm[fed + k])
                            codec.queueInputBuffer(idx, 0, n * 2, time, 0)
                            fed += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val copy = ByteBuffer.allocate(info.size)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            copy.put(buf); copy.flip()
                            samples += copy to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return Encoded(outFormat ?: throw IllegalStateException("The audio encoder gave no format"), samples)
    }

    private fun encodeVideo(
        pictures: List<Bitmap>, captions: List<String>, seconds: List<Double>, muxer: MediaMuxer, audio: Encoded?, progress: (String) -> Unit,
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val argb = IntArray(W * H)
        val y = ByteArray(W * H)
        val u = ByteArray((W / 2) * (H / 2))
        val v = ByteArray((W / 2) * (H / 2))
        val frameCounts = seconds.map { (it * FPS).toInt().coerceAtLeast(1) }
        val totalFrames = frameCounts.sum()
        val layouts = captions.map { caption(it) }

        var videoTrack = -1
        var audioTrack = -1
        var started = false
        val pending = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
        val info = MediaCodec.BufferInfo()
        var frameNo = 0
        var inputDone = false

        fun write(buf: ByteBuffer, bi: MediaCodec.BufferInfo) {
            if (!started) {
                val copy = ByteBuffer.allocate(bi.size); buf.position(bi.offset); buf.limit(bi.offset + bi.size); copy.put(buf); copy.flip()
                pending += copy to MediaCodec.BufferInfo().apply { set(0, bi.size, bi.presentationTimeUs, bi.flags) }
            } else {
                buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                muxer.writeSampleData(videoTrack, buf, bi)
            }
        }

        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        if (frameNo >= totalFrames) {
                            codec.queueInputBuffer(idx, 0, 0, frameNo * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            // Which scene, and how far into it (for the slow zoom).
                            var scene = 0
                            var into = frameNo
                            while (into >= frameCounts[scene]) { into -= frameCounts[scene]; scene++ }
                            drawFrame(canvas, paint, pictures[scene], layouts[scene], into.toFloat() / frameCounts[scene])
                            frame.getPixels(argb, 0, W, 0, 0, W, H)
                            VideoPlan.argbToYuv420(argb, W, H, y, u, v)
                            val size = putYuv(codec, idx, y, u, v)
                            codec.queueInputBuffer(idx, 0, size, frameNo * 1_000_000L / FPS, 0)
                            frameNo++
                            if (frameNo % (FPS * 2) == 0) progress("Making the video… ${frameNo * 100 / totalFrames}%")
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        if (audio != null) audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                        started = true
                        audio?.samples?.forEach { (b, bi) -> muxer.writeSampleData(audioTrack, b, bi) }
                        pending.forEach { (b, bi) -> muxer.writeSampleData(videoTrack, b, bi) }
                        pending.clear()
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) write(buf, info)
                        codec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
            if (!started) throw IllegalStateException("The phone's video encoder produced no video")
        } finally {
            codec.stop()
            codec.release()
            frame.recycle()
        }
    }

    /** Copies a YUV 4:2:0 frame into the encoder's input, whatever plane layout it uses. Returns the byte size. */
    private fun putYuv(codec: MediaCodec, index: Int, y: ByteArray, u: ByteArray, v: ByteArray): Int {
        val image = codec.getInputImage(index)
        if (image != null) {
            val planes = image.planes
            val cw = W / 2
            val ch = H / 2
            val py = planes[0]
            val yb = py.buffer
            for (row in 0 until H) {
                if (py.pixelStride == 1) {
                    yb.position(row * py.rowStride); yb.put(y, row * W, W)
                } else for (col in 0 until W) yb.put(row * py.rowStride + col * py.pixelStride, y[row * W + col])
            }
            for ((plane, src) in listOf(planes[1] to u, planes[2] to v)) {
                val b = plane.buffer
                for (row in 0 until ch) for (col in 0 until cw) {
                    val pos = row * plane.rowStride + col * plane.pixelStride
                    if (pos < b.capacity()) b.put(pos, src[row * cw + col])
                }
            }
            return W * H * 3 / 2
        }
        // Older encoders without image access: planar YUV 4:2:0 (I420).
        val buf = codec.getInputBuffer(index)!!
        buf.clear()
        buf.put(y); buf.put(u); buf.put(v)
        return W * H * 3 / 2
    }

    /** One frame: the scene's picture with a slow zoom (Ken Burns) and its narration as a caption. */
    private fun drawFrame(canvas: Canvas, paint: Paint, picture: Bitmap, caption: StaticLayout?, t: Float) {
        canvas.drawColor(Color.BLACK)
        val zoom = 1f + 0.08f * t
        val m = Matrix()
        m.postScale(zoom, zoom, W / 2f, H / 2f)
        canvas.drawBitmap(picture, m, paint)
        if (caption != null) {
            val pad = 28f
            val top = H - caption.height - 2 * pad - 60f
            canvas.drawRoundRect(RectF(24f, top, W - 24f, top + caption.height + 2 * pad), 28f, 28f, Paint().apply { color = 0xB0000000.toInt() })
            canvas.save()
            canvas.translate(24f + pad, top + pad)
            caption.draw(canvas)
            canvas.restore()
        }
    }

    private fun caption(text: String): StaticLayout? {
        val t = text.trim().take(220)
        if (t.isEmpty()) return null
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
        return StaticLayout.Builder.obtain(t, 0, t.length, tp, W - 48 - 56).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(5).build()
    }
}
