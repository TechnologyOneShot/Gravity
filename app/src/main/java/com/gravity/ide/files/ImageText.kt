package com.gravity.ide.files

import android.content.Context
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.gravity.ide.core.TextQuality
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the text in a design picture on the phone (ML Kit, Hindi + English, no internet), with
 * where each piece is on the screen, for phone models that cannot see pictures. Call off the main thread.
 */
object ImageText {
    fun describe(context: Context, file: File): String {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
            val w = bmp.width.coerceAtLeast(1)
            val h = bmp.height.coerceAtLeast(1)
            return result.textBlocks
                .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                .joinToString("\n") { b ->
                    val box = b.boundingBox
                    val where = if (box == null) "" else {
                        val y = box.centerY() * 100 / h
                        val x = box.centerX() * 100 / w
                        val side = when {
                            x < 35 -> "left"
                            x > 65 -> "right"
                            else -> "center"
                        }
                        val big = box.height() * 100 / h >= 6
                        " — $y% from the top, $side${if (big) ", large" else ""}"
                    }
                    "\"${TextQuality.fixOcrDigits(b.text.replace('\n', ' '))}\"$where"
                }
        } finally {
            recognizer.close()
            bmp.recycle()
        }
    }
}
