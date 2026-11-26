package com.gravity.ide.ai.image

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import com.gravity.ide.BuildConfig
import com.gravity.ide.ai.local.LlamaBridge
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.LocalModelEntity
import com.gravity.ide.models.ModelManager
import com.gravity.ide.models.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Realistic pictures made on the phone, without internet: Stable Diffusion 1.5 (Realistic Vision
 * 5.1) with the LCM speed-up (4 steps) and the small TAESD decoder, run by stable-diffusion.cpp.
 * Tested on an Arm CPU (4 cores, like a phone's fast cores): a 512x512 picture in about 2.4 minutes,
 * 384x384 in about 1.3, about 2.5 GB of memory while it is made.
 */
class PhonePictures(
    private val context: Context,
    private val db: GravityDatabase,
    private val models: ModelManager,
    private val localHost: LocalModelHost,
    private val crashReports: com.gravity.ide.CrashReports,
    private val threads: suspend () -> Int,
) {
    /** One file of the picture model, from Hugging Face (size and SHA-256 come from its API). */
    data class Part(val repo: String, val path: String, val label: String, val license: String) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    sealed interface State {
        /** This build or phone cannot run it ([why]). */
        data class Unavailable(val why: String) : State
        data object NotDownloaded : State
        data class Downloading(val percent: Int, val failed: String? = null) : State
        data object Ready : State
    }

    companion object {
        val PARTS = listOf(
            Part("SG161222/Realistic_Vision_V5.1_noVAE", "Realistic_Vision_V5.1_fp16-no-ema.safetensors", "Realistic Vision 5.1 (the picture model)", "CreativeML OpenRAIL-M"),
            Part("latent-consistency/lcm-lora-sdv1-5", "pytorch_lora_weights.safetensors", "LCM LoRA (pictures in 4 steps)", "OpenRAIL++"),
            Part("madebyollin/taesd", "diffusion_pytorch_model.safetensors", "TAESD (fast picture decoder)", "MIT"),
        )
        const val DOWNLOAD_LABEL = "about 2.2 GB"
        const val STEPS = 4
        const val CFG = 1.5f
        const val METHOD = "lcm"
        /** Kept out of every picture (applies because CFG is above 1). */
        const val NEGATIVE = "cartoon, drawing, painting, blurry, deformed, disfigured, extra limbs, extra fingers, text, watermark, nsfw, nudity"
        /** Memory a picture needs beside the chat model (measured about 2.5 GB peak, with margin). */
        private const val NEEDS_BYTES = 3_200L * 1024 * 1024
    }

    private val mutex = Mutex()
    @Volatile private var handle = 0L

    /** Why pictures cannot be made on this phone at all, or null. */
    val unavailableReason: String? by lazy {
        when {
            !BuildConfig.IMAGE_BUNDLED -> "this build has no on-phone picture engine"
            android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && !LlamaBridge.armv82() ->
                "this phone's CPU is too old (it needs Armv8.2 dot product and fp16)"
            else -> NativeImage.loadError
        }
    }

    private fun matches(row: LocalModelEntity, part: Part) = row.repoId == part.repo && row.name == part.fileName

    suspend fun state(): State {
        unavailableReason?.let { return State.Unavailable(it) }
        val rows = db.models().pictureFiles()
        val mine = PARTS.map { p -> rows.lastOrNull { matches(it, p) } }
        if (mine.all { it?.status == ModelStatus.READY.name && File(it.filePath).isFile }) return State.Ready
        if (mine.all { it == null }) return State.NotDownloaded
        val total = PARTS.indices.sumOf { i -> mine[i]?.sizeBytes ?: 0L }.coerceAtLeast(1)
        val done = PARTS.indices.sumOf { i -> mine[i]?.let { if (it.status == ModelStatus.READY.name) it.sizeBytes else it.downloadedBytes } ?: 0L }
        val failed = mine.firstOrNull { it?.status == ModelStatus.FAILED.name }?.error
        return State.Downloading((done * 100 / total).toInt().coerceIn(0, 100), failed)
    }

    /** Starts (or restarts) downloading the parts that are not ready. */
    suspend fun download() = withContext(Dispatchers.IO) {
        unavailableReason?.let { throw IllegalStateException(it) }
        val rows = db.models().pictureFiles()
        for (part in PARTS) {
            val row = rows.lastOrNull { matches(it, part) }
            when {
                row != null && row.status == ModelStatus.READY.name && File(row.filePath).isFile -> Unit
                row != null && row.status != ModelStatus.FAILED.name -> models.resume(row.id)
                else -> {
                    row?.let { models.delete(it.id) }
                    val file = models.listHubFiles(part.repo, ".safetensors").firstOrNull { it.path == part.path }
                        ?: throw IllegalStateException("${part.label} is not on Hugging Face any more (${part.repo}/${part.path})")
                    models.enqueueDownload(
                        "https://huggingface.co/${part.repo}/resolve/main/${part.path}", part.fileName, file.sha256, file.size,
                        part.repo, part.license, role = "picture",
                    )
                }
            }
        }
    }

    /** Deletes the downloaded picture model files. */
    suspend fun delete() = withContext(Dispatchers.IO) {
        db.models().pictureFiles().forEach { models.delete(it.id) }
    }

    /**
     * Makes a [width]x[height] picture of [prompt] (English works best). [onStep] after each of the
     * steps. Throws with a plain message if it cannot.
     */
    suspend fun make(prompt: String, width: Int, height: Int, onStep: (Int, Int) -> Unit): Bitmap = mutex.withLock {
        if (state() != State.Ready) throw IllegalStateException("the on-phone picture model is not downloaded (Settings → AI → Pictures and videos)")
        val rows = db.models().pictureFiles()
        val (model, lora, taesd) = PARTS.map { p -> rows.last { matches(it, p) && it.status == ModelStatus.READY.name }.filePath }
        // Make room: the chat model goes if the phone is short of memory (it loads again for the next reply).
        val loaded = localHost.loadedOrNull()
        if (loaded != null && com.gravity.ide.core.ContextPlanner.reallyFree(availableBytes(), loaded.fileBytes) < NEEDS_BYTES) localHost.unload()
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Gravity:phone-picture").apply { setReferenceCounted(false); acquire(20 * 60_000L) }
        try {
            crashReports.during("making a picture on the phone (${width}x$height, $STEPS steps)") {
                val n = threads()
                withContext(Dispatchers.Default) {
                    val h = NativeImage.load(model, lora, taesd, n, "q8_0")
                    if (h == 0L) throw IllegalStateException("the picture model could not be prepared: ${NativeImage.lastError()}")
                    handle = h
                    try {
                        val rgb = NativeImage.generate(
                            h, prompt, NEGATIVE, width, height, STEPS, CFG, (Math.random() * Int.MAX_VALUE).toLong(), METHOD, 1.0f,
                        ) { step, steps -> onStep(step, steps) } ?: throw IllegalStateException(NativeImage.lastError().ifBlank { "no picture came out" })
                        toBitmap(rgb, NativeImage.lastWidth(), NativeImage.lastHeight())
                    } finally {
                        handle = 0L
                        NativeImage.unload(h)
                    }
                }
            }
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    /** Stops the picture being made (it then fails with "cancelled"). */
    fun cancel() {
        handle.takeIf { it != 0L }?.let { NativeImage.cancel(it) }
    }

    private fun availableBytes(): Long = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }.availMem

    private fun toBitmap(rgb: ByteArray, w: Int, h: Int): Bitmap {
        require(w > 0 && h > 0 && rgb.size >= w * h * 3) { "the picture came out empty" }
        val px = IntArray(w * h)
        for (i in px.indices) {
            px[i] = (0xff shl 24) or ((rgb[i * 3].toInt() and 0xff) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xff) shl 8) or (rgb[i * 3 + 2].toInt() and 0xff)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
