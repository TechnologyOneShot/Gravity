package com.gravity.ide.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ScreenshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ScreenshotStore(context: Context, private val db: GravityDatabase, private val events: EventLog) {
    val dir: File = File(context.filesDir, "screenshots").apply { mkdirs() }

    suspend fun save(bitmap: Bitmap, label: String, packageName: String?, testRunId: Long? = null): ScreenshotEntity = withContext(Dispatchers.IO) {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".png"
        val file = File(dir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val entity = ScreenshotEntity(
            filePath = file.absolutePath, packageName = packageName, label = label, width = bitmap.width, height = bitmap.height,
            testRunId = testRunId, createdAt = System.currentTimeMillis(),
        )
        val id = db.screenshots().insert(entity)
        events.log(EventCategory.TEST, "Screenshot captured", "$label ${bitmap.width}×${bitmap.height}${packageName?.let { " · $it" } ?: ""}")
        entity.copy(id = id)
    }

    suspend fun captureNow(label: String, packageName: String?): ScreenshotEntity = save(ScreenCaptureController.capture(), label, packageName)

    suspend fun delete(s: ScreenshotEntity) = withContext(Dispatchers.IO) {
        File(s.filePath).delete()
        db.screenshots().delete(s.id)
    }

    /** JPEG, downscaled, base64 — the payload sent to a vision model for analysis. */
    fun encodeForVision(path: String, maxSide: Int = 1280): String {
        val bmp = BitmapFactory.decodeFile(path) ?: throw IllegalStateException("Cannot decode $path")
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

/** Pixel comparison of two screenshots (visual regression). */
object ImageDiff {
    data class Result(val differentPercent: Double, val diffImage: Bitmap, val comparedWidth: Int, val comparedHeight: Int)

    fun compare(a: Bitmap, b: Bitmap, width: Int = 360, threshold: Int = 32): Result {
        val h = (width.toFloat() * a.height / a.width).toInt().coerceAtLeast(1)
        val sa = Bitmap.createScaledBitmap(a, width, h, true)
        val sb = Bitmap.createScaledBitmap(b, width, h, true)
        val pa = IntArray(width * h).also { sa.getPixels(it, 0, width, 0, 0, width, h) }
        val pb = IntArray(width * h).also { sb.getPixels(it, 0, width, 0, 0, width, h) }
        val out = IntArray(width * h)
        var diff = 0
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val d = abs(Color.red(ca) - Color.red(cb)) + abs(Color.green(ca) - Color.green(cb)) + abs(Color.blue(ca) - Color.blue(cb))
            if (d > threshold) {
                diff++
                out[i] = Color.rgb(255, 40, 80)
            } else {
                val g = (Color.red(ca) + Color.green(ca) + Color.blue(ca)) / 3 / 3
                out[i] = Color.rgb(g, g, g)
            }
        }
        val img = Bitmap.createBitmap(out, width, h, Bitmap.Config.ARGB_8888)
        return Result(diff * 100.0 / pa.size, img, width, h)
    }
}
