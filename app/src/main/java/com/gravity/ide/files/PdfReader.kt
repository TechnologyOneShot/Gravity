package com.gravity.ide.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.gravity.ide.core.TextQuality
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads a PDF on the phone, for every AI (phone models read text, not pictures):
 * 1. the PDF's own text layer (PdfBox), when it is there and readable;
 * 2. otherwise on-device OCR (ML Kit text recognition, Devanagari + Latin, bundled in the APK:
 *    no internet, no limits) of each rendered page: scanned PDFs and Hindi typed in old fonts.
 * Also renders the first pages as pictures for cloud AI that can look at them.
 */
object PdfReader {

    data class Result(
        val text: String,
        val pages: Int,
        /** "text layer" or "on-device OCR". */
        val method: String,
        val pagesRead: Int,
        val previews: List<Bitmap>,
        /** What the text layer gave (characters, or why it could not be read): for diagnostics. */
        val layerInfo: String = "",
    )

    /** Pages read with OCR at most (each takes about a second on a phone). */
    private const val MAX_OCR_PAGES = 60
    private const val MAX_TEXT_PAGES = 500

    fun read(context: Context, uri: Uri, previewPages: Int = 3): Result {
        // PdfRenderer and PdfBox need a seekable file: copy the picked document to the cache first.
        val tmp = File.createTempFile("pdf-", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalArgumentException("Cannot open the PDF")
            var pages = 0
            var layerText = ""
            val layer = runCatching {
                PDFBoxResourceLoader.init(context.applicationContext)
                PDDocument.load(tmp).use { doc ->
                    pages = doc.numberOfPages
                    val stripper = PDFTextStripper()
                    stripper.sortByPosition = true
                    stripper.startPage = 1
                    stripper.endPage = minOf(pages, MAX_TEXT_PAGES)
                    layerText = stripper.getText(doc)
                }
            }
            val layerInfo = layer.exceptionOrNull()?.let { "text layer unreadable: ${it.javaClass.simpleName}: ${it.message}" }
                ?: "text layer: ${layerText.trim().length} characters: ${layerText.trim().replace('\n', ' ').take(120)}"
            val previews = ArrayList<Bitmap>()
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pages == 0) pages = renderer.pageCount
                    for (i in 0 until minOf(previewPages, renderer.pageCount)) previews += render(renderer, i, 1600)
                    if (!TextQuality.pdfTextLooksBroken(layerText, pages)) {
                        return Result(layerText.trim(), pages, "text layer", minOf(pages, MAX_TEXT_PAGES), previews, layerInfo)
                    }
                    // No usable text layer: read the pages with on-device OCR.
                    val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                    try {
                        val sb = StringBuilder()
                        val count = minOf(renderer.pageCount, MAX_OCR_PAGES)
                        for (i in 0 until count) {
                            val bmp = render(renderer, i, 2000)
                            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS).text
                            if (i >= previews.size) bmp.recycle()
                            if (text.isNotBlank()) sb.append("--- Page ").append(i + 1).append(" ---\n").append(text.trim()).append("\n\n")
                        }
                        return Result(sb.toString().trim(), pages, "on-device OCR", count, previews, layerInfo)
                    } finally {
                        recognizer.close()
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun render(renderer: PdfRenderer, index: Int, longSide: Int): Bitmap = renderer.openPage(index).use { page ->
        val scale = longSide.toFloat() / maxOf(page.width, page.height)
        val bmp = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }
}
