package com.gravity.ide.voice

import android.content.Context
import com.gravity.ide.BuildConfig
import com.gravity.ide.data.db.LocalModelEntity
import com.gravity.ide.models.ModelStatus
import java.io.File
import java.io.IOException

/**
 * Yamini's natural voice, made on the phone without internet or any account: Piper neural voices
 * (VITS) run by sherpa-onnx. Measured on an Arm CPU with 2 threads: the first sentence is ready in
 * about 0.1–0.2 s and a sentence takes about 8% of its spoken length to make, so speaking starts at
 * once. Voices are downloaded once (about 67 MB each, SHA-256 checked) and work offline.
 * The app reaches them through its own Android voice service ([YaminiVoiceService]).
 */
object NeuralVoices {
    data class Pack(
        val id: String,
        val label: String,
        /** "hi" or "en". */
        val lang: String,
        val gender: String,
        /** Folder inside the archive, and the model file in it. */
        val dir: String,
        val model: String,
        val sha256: String,
        val size: Long,
        val note: String,
    ) {
        val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$dir.tar.bz2"
        val archiveName get() = "$dir.tar.bz2"
    }

    private const val LICENSE = "Voice: Piper (MIT); voice data CC BY-NC-SA 4.0 (free, non-commercial use)"

    val packs = listOf(
        Pack(
            "hi-female", "Priyamvada (Hindi, female)", "hi", "female", "vits-piper-hi_IN-priyamvada-medium", "hi_IN-priyamvada-medium.onnx",
            "399d91cc97eb288725633261f26b715f9a971e3bf7ec4fa1d7910cd0080d37eb", 67_240_610L, "Hindi and Hinglish",
        ),
        Pack(
            "hi-male", "Pratham (Hindi, male)", "hi", "male", "vits-piper-hi_IN-pratham-medium", "hi_IN-pratham-medium.onnx",
            "2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648", 67_238_438L, "Hindi and Hinglish",
        ),
        Pack(
            "en-female", "Hi-Fi Captain (English, female)", "en", "female", "vits-piper-en_US-hfc_female-medium", "en_US-hfc_female-medium.onnx",
            "3fffdceb0c65bd9415a085d09c3cb88cc82f9d74a6ca453f8ce7fc5eaee81ff8", 67_228_166L, "English",
        ),
        Pack(
            "en-male", "Hi-Fi Captain (English, male)", "en", "male", "vits-piper-en_US-hfc_male-medium", "en_US-hfc_male-medium.onnx",
            "76388f84acfca8ba5c0ed1636a26ada14c598abd52e76f110d4756fe326fc5f2", 67_214_049L, "English",
        ),
    )

    fun pack(id: String?): Pack? = packs.firstOrNull { it.id == id }

    /** True when the voice engine is in this APK and its library loads. */
    val available: Boolean by lazy {
        BuildConfig.VOICE_BUNDLED && runCatching { System.loadLibrary("sherpa-onnx-jni") }.isSuccess
    }

    val unavailableReason: String?
        get() = when {
            !BuildConfig.VOICE_BUNDLED -> "This copy of Yamini was built without the offline voice engine. Install yamini-debug.apk from the latest release."
            !available -> "The offline voice engine could not start on this phone."
            else -> null
        }

    fun voicesDir(context: Context) = File(context.filesDir, "voices").apply { mkdirs() }

    /** Where a downloaded pack is unpacked. */
    fun packDir(context: Context, pack: Pack) = File(voicesDir(context), pack.dir)

    /** A pack is ready when its model, tokens and pronunciation data are unpacked. */
    fun isReady(context: Context, pack: Pack): Boolean {
        val d = packDir(context, pack)
        return File(d, pack.model).isFile && File(d, "tokens.txt").isFile && File(d, "espeak-ng-data/phontab").isFile
    }

    private fun prefs(context: Context) = context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    /** Use the natural voice when its pack is downloaded (on by default). */
    fun enabled(context: Context) = prefs(context).getBoolean("neural", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("neural", on).apply()

    /** The chosen pack for a language ("hi" or "en"). */
    fun chosen(context: Context, lang: String): Pack =
        pack(prefs(context).getString("neural_$lang", null))?.takeIf { it.lang == lang } ?: packs.first { it.lang == lang }

    fun choose(context: Context, pack: Pack) = prefs(context).edit().putString("neural_${pack.lang}", pack.id).apply()

    /** The pack to speak [lang] with now, or null (not chosen, not downloaded, or no engine). */
    fun active(context: Context, lang: String): Pack? =
        chosen(context, lang).takeIf { available && enabled(context) && isReady(context, it) }

    /** Unpacks a downloaded pack archive (tar.bz2) safely: no paths outside the pack's folder. */
    fun unpack(context: Context, archive: File, pack: Pack) {
        val root = voicesDir(context)
        val tmp = File(root, pack.dir + ".unpacking").apply { deleteRecursively(); mkdirs() }
        val base = tmp.canonicalFile
        var total = 0L
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16)),
        ).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isSymbolicLink || e.isLink) continue
                val out = File(tmp, e.name).canonicalFile
                if (!out.path.startsWith(base.path + File.separator) && out != base) throw IOException("Unsafe path in the voice archive: ${e.name}")
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                total += e.size
                if (total > 400L * 1024 * 1024) throw IOException("The voice archive unpacks to more than 400 MB")
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
        // The archive holds one folder named like the pack.
        val inner = File(tmp, pack.dir).takeIf { it.isDirectory } ?: tmp
        val dest = packDir(context, pack)
        dest.deleteRecursively()
        if (!inner.renameTo(dest)) throw IOException("Could not move the voice into place")
        tmp.deleteRecursively()
        if (!isReady(context, pack)) throw IOException("The voice archive is missing files")
    }

    /** The download row of a pack (role "voice"), if any. */
    fun rowFor(rows: List<LocalModelEntity>, pack: Pack) = rows.lastOrNull { it.name == pack.archiveName }

    fun statusOf(context: Context, rows: List<LocalModelEntity>, pack: Pack): String = when {
        isReady(context, pack) -> "ready"
        else -> rowFor(rows, pack)?.status?.let {
            when (it) {
                ModelStatus.QUEUED.name, ModelStatus.DOWNLOADING.name, ModelStatus.VERIFYING.name -> "downloading"
                ModelStatus.FAILED.name -> "failed"
                ModelStatus.PAUSED.name -> "paused"
                else -> "none"
            }
        } ?: "none"
    }

    // ---- The engine -------------------------------------------------------------------------

    private val engines = HashMap<String, com.k2fsa.sherpa.onnx.OfflineTts>()

    /** Loads [pack] (about 0.8 s the first time; kept for the next sentences). */
    fun engine(context: Context, pack: Pack): com.k2fsa.sherpa.onnx.OfflineTts = synchronized(engines) {
        engines[pack.id]?.let { return it }
        check(available) { unavailableReason ?: "no voice engine" }
        val d = packDir(context, pack)
        val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                    model = File(d, pack.model).absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                // Two threads: the chat model may be writing the next sentence at the same time.
                numThreads = 2,
            ),
        )
        com.k2fsa.sherpa.onnx.OfflineTts(null, cfg).also { engines[pack.id] = it }
    }

    /** Loads the voice for [lang] in the background so the first sentence does not wait. */
    fun preload(context: Context, lang: String) {
        val pack = active(context, lang) ?: return
        Thread { runCatching { engine(context.applicationContext, pack) } }.apply { isDaemon = true; name = "voice-preload" }.start()
    }

    /** Frees the loaded voices (for memory). */
    fun release() = synchronized(engines) {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
    }

    /**
     * Makes [text] at [speed] (1 = normal) and [pitch] (1 = normal) as 16-bit PCM, giving it to
     * [chunk] as it is made. Pitch: made at speed/pitch, then resampled by pitch, so the speed
     * stays right and the voice is higher or lower. Returns the sample rate.
     */
    fun speak(
        context: Context, pack: Pack, text: String, speed: Float, pitch: Float,
        stopped: () -> Boolean, chunk: (ByteArray) -> Unit,
    ): Int {
        val tts = engine(context, pack)
        val p = pitch.coerceIn(0.7f, 1.4f)
        val shift = kotlin.math.abs(p - 1f) > 0.02f
        val makeSpeed = (if (shift) speed / p else speed).coerceIn(0.5f, 2.5f)
        // Whole phrase at once (a phrase takes about 0.1 s). Not generateWithCallback: its native
        // side looks the callback up as invoke([F)Ljava/lang/Integer;, which Kotlin 2's lambdas do
        // not have, and the lookup error left pending crashed the app.
        val audio = tts.generate(text, 0, makeSpeed)
        if (!stopped() && audio.samples.isNotEmpty()) chunk(pcm16(if (shift) resample(audio.samples, p) else audio.samples))
        return tts.sampleRate()
    }

    /** Linear resampling by [factor] (> 1: fewer samples, a higher voice when played at the same rate). */
    fun resample(x: FloatArray, factor: Float): FloatArray {
        if (x.isEmpty()) return x
        val n = (x.size / factor).toInt().coerceAtLeast(1)
        return FloatArray(n) { i ->
            val pos = i * factor
            val a = pos.toInt().coerceAtMost(x.size - 1)
            val b = (a + 1).coerceAtMost(x.size - 1)
            val f = pos - a
            x[a] * (1 - f) + x[b] * f
        }
    }

    fun pcm16(x: FloatArray): ByteArray {
        val out = ByteArray(x.size * 2)
        for (i in x.indices) {
            val s = (x[i].coerceIn(-1f, 1f) * 32767).toInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    const val LICENSE_NOTE = LICENSE
}
