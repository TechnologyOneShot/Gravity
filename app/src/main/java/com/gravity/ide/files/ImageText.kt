package com.gravity.ide.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.gravity.ide.core.DesignReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads a design picture on the phone for phone models, which cannot see pictures: ML Kit reads
 * its text lines (Hindi + English, no internet) and DesignReader measures the rest (background,
 * photos, cards, bars, icons, text colors and sizes). Call off the main thread.
 */
object ImageText {
    /** The OCR works best on a picture of about this width; the measuring needs no more than this. */
    private const val OCR_WIDTH = 1440
    private const val MEASURE_WIDTH = 720

    fun describe(context: Context, file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= OCR_WIDTH) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
        val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
            val k = if (bmp.width > MEASURE_WIDTH) MEASURE_WIDTH.toFloat() / bmp.width else 1f
            val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                DesignReader.TextLine(
                    line.text, (box.left * k).toInt(), (box.top * k).toInt(), (box.right * k).toInt(), (box.bottom * k).toInt(),
                    confidenceOf(line),
                )
            }
            val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, MEASURE_WIDTH, (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
            try {
                val px = IntArray(small.width * small.height)
                small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                return DesignReader.describe(DesignReader.Pixels(small.width, small.height, px), lines)
            } finally {
                if (small !== bmp) small.recycle()
            }
        } finally {
            recognizer.close()
            bmp.recycle()
        }
    }

    /** ML Kit's confidence for a line (0..1), where this version of the library gives one. */
    private fun confidenceOf(line: Any): Float? = runCatching { line.javaClass.getMethod("getConfidence").invoke(line) as? Float }.getOrNull()?.takeIf { it > 0f }
}
