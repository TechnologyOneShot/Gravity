package com.gravity.ide.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.caverock.androidsvg.SVG
import com.gravity.ide.core.MakeFiles
import com.gravity.ide.core.SpeechText
import com.gravity.ide.core.WavJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Makes files on the phone from what the AI wrote: PDF documents, ZIP archives, PNG pictures
 * (from an SVG the AI drew) and WAV audio (the phone's text-to-speech voice). Everything runs
 * on the device: no cloud service, no account, no limits. Files go to files/created/.
 */
object FileMaker {

    fun dir(context: Context): File = File(context.filesDir, "created").apply { mkdirs() }

    internal fun unique(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, "$base-${n++}$ext")
        return f
    }

    internal fun clean(name: String) = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "-").trim('-').take(60).ifBlank { "file" }

    /** A4 PDF from Markdown-ish text: headings, bullet points, paragraphs and code; Hindi and English both render. */
    fun pdf(context: Context, name: String, markdown: String): File {
        val file = unique(dir(context), clean(name) + ".pdf")
        val pageW = 595
        val pageH = 842
        val margin = 50
        val width = pageW - 2 * margin
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f; color = Color.rgb(20, 20, 20) }
        val styles = mapOf(
            "h1" to TextPaint(body).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD },
            "h2" to TextPaint(body).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD },
            "h3" to TextPaint(body).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD },
            "code" to TextPaint(body).apply { textSize = 9.5f; typeface = Typeface.MONOSPACE; color = Color.rgb(40, 40, 60) },
            "li" to body,
            "p" to body,
        )
        val footer = TextPaint(body).apply { textSize = 8.5f; color = Color.GRAY }
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun finish() {
            page?.let { p ->
                p.canvas.drawText("$pageNo", pageW / 2f, pageH - 24f, footer.apply { textAlign = Paint.Align.CENTER })
                doc.finishPage(p)
            }
        }
        fun newPage() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = margin.toFloat()
        }
        newPage()
        val blocks = MakeFiles.pdfBlocks(markdown).ifEmpty { listOf(MakeFiles.Block("p", markdown.trim())) }
        for (b in blocks) {
            val paint = styles[b.style] ?: body
            val indent = if (b.style == "li") 12 else 0
            val before = when (b.style) { "h1" -> 4f; "h2" -> 10f; "h3" -> 8f; else -> 0f }
            val after = when (b.style) { "h1" -> 10f; "li" -> 3f; else -> 8f }
            val layout = StaticLayout.Builder.obtain(b.text, 0, b.text.length, paint, width - indent)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1.1f).build()
            y += before
            var line = 0
            while (line < layout.lineCount) {
                val room = pageH - margin - y
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= room) last++
                if (last == line) {
                    if (y <= margin + 1f) last = line + 1 else { newPage(); continue }
                }
                val top = layout.getLineTop(line)
                val bottom = layout.getLineBottom(last - 1)
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate((margin + indent).toFloat(), y - top)
                canvas.clipRect(0, top, width, bottom)
                layout.draw(canvas)
                canvas.restore()
                y += (bottom - top)
                line = last
            }
            y += after
        }
        finish()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** ZIP of named files (names are already safe: no "..", no leading "/"). */
    fun zip(context: Context, name: String, files: List<Pair<String, ByteArray>>): File {
        require(files.isNotEmpty()) { "There are no files to put in the ZIP" }
        val file = unique(dir(context), clean(name) + ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((path, bytes) in files) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    /** ZIP of a whole folder (an app Gravity built). */
    fun zipFolder(context: Context, name: String, folder: File): File {
        val base = folder.canonicalFile
        val files = base.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
            .map { it.relativeTo(base).invariantSeparatorsPath to it.readBytes() }.toList()
        return zip(context, name, files)
    }

    /** The AI's SVG drawing as a [w]x[h] picture (fitted inside, centred, on [background]). */
    fun renderSvg(svgText: String, w: Int, h: Int, background: Int = Color.WHITE): Bitmap {
        val svg = runCatching { SVG.getFromString(svgText) }.getOrElse { throw IllegalArgumentException("The AI's drawing (SVG) could not be read: ${it.message}") }
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        svg.renderToCanvas(canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        return bmp
    }

    /**
     * Each text read aloud with one voice (the user's voice settings), as WAV bytes; null for a text
     * that could not be read. Throws if the phone has no working text-to-speech.
     */
    suspend fun speechClips(context: Context, texts: List<String>, locale: Locale): List<ByteArray?> {
        val ready = CompletableDeferred<Int>()
        val tts = withContext(Dispatchers.Main) { TextToSpeech(context.applicationContext) { status -> ready.complete(status) } }
        try {
            val status = withTimeout(15_000) { ready.await() }
            if (status != TextToSpeech.SUCCESS) throw IllegalStateException("This phone has no working text-to-speech engine (Android Settings → Text-to-speech).")
            VoicePrefs.apply(context, tts, locale.language == "hi")
            return texts.mapIndexed { i, text ->
                val clean = SpeechText.clean(text).take(TextToSpeech.getMaxSpeechInputLength() - 100)
                if (clean.isBlank()) return@mapIndexed null
                val tmp = File.createTempFile("clip-$i-", ".wav", context.cacheDir)
                try {
                    val done = CompletableDeferred<Unit>()
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { done.complete(Unit) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { done.completeExceptionally(IllegalStateException("Text-to-speech could not read scene ${i + 1}")) }
                        override fun onError(utteranceId: String?, errorCode: Int) { done.completeExceptionally(IllegalStateException("Text-to-speech error $errorCode")) }
                    })
                    if (tts.synthesizeToFile(clean, Bundle(), tmp, "clip-$i") != TextToSpeech.SUCCESS) return@mapIndexed null
                    runCatching { withTimeout(120_000) { done.await() } }.getOrNull() ?: return@mapIndexed null
                    tmp.readBytes()
                } finally {
                    tmp.delete()
                }
            }
        } finally {
            withContext(Dispatchers.Main) { tts.shutdown() }
        }
    }

    /** PNG from an SVG the AI drew (AndroidSVG renders it; 1024 px on the long side, white background). */
    fun png(context: Context, name: String, svgText: String, longSide: Int = 1024): File {
        val svg = runCatching { SVG.getFromString(svgText) }.getOrElse { throw IllegalArgumentException("The AI's drawing (SVG) could not be read: ${it.message}") }
        val box = svg.documentViewBox
        val ratio = when {
            box != null && box.width() > 0 && box.height() > 0 -> box.width() / box.height()
            svg.documentWidth > 0 && svg.documentHeight > 0 -> svg.documentWidth / svg.documentHeight
            else -> 1f
        }
        val w = if (ratio >= 1f) longSide else (longSide * ratio).toInt().coerceAtLeast(1)
        val h = if (ratio >= 1f) (longSide / ratio).toInt().coerceAtLeast(1) else longSide
        if (box == null && svg.documentWidth > 0 && svg.documentHeight > 0) svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        svg.renderToCanvas(canvas, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        val file = unique(dir(context), clean(name) + ".png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    /**
     * WAV audio of [text] read by the phone's text-to-speech in [locale] (the best installed voice,
     * see VoicePicker). Long text is read in parts and joined.
     */
    suspend fun audio(context: Context, name: String, text: String, locale: Locale, voiceName: String? = null, rate: Float = 1f): File {
        val clean = SpeechText.clean(text)
        require(clean.isNotBlank()) { "There is no text to read aloud" }
        val ready = CompletableDeferred<Int>()
        val tts = withContext(Dispatchers.Main) { TextToSpeech(context.applicationContext) { status -> ready.complete(status) } }
        try {
            val status = withTimeout(15_000) { ready.await() }
            if (status != TextToSpeech.SUCCESS) throw IllegalStateException("This phone has no working text-to-speech engine (Android Settings → Text-to-speech).")
            // The user's voice settings (Settings → Voice & speech): voice, speed, pitch, style.
            val hindi = locale.language == "hi"
            VoicePrefs.apply(context, tts, hindi)
            if (voiceName != null) VoicePicker.choose(tts, locale, voiceName)?.let { tts.setVoice(it) }
            if (rate != 1f) tts.setSpeechRate(rate)
            val parts = SpeechText.chunks(clean, minOf(3500, TextToSpeech.getMaxSpeechInputLength() - 100))
            val tmp = parts.indices.map { File.createTempFile("tts-$it-", ".wav", context.cacheDir) }
            try {
                for ((i, part) in parts.withIndex()) {
                    val done = CompletableDeferred<Unit>()
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { done.complete(Unit) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { done.completeExceptionally(IllegalStateException("Text-to-speech could not read part ${i + 1}")) }
                        override fun onError(utteranceId: String?, errorCode: Int) { done.completeExceptionally(IllegalStateException("Text-to-speech error $errorCode")) }
                    })
                    val r = tts.synthesizeToFile(part, Bundle(), tmp[i], "make-$i")
                    if (r != TextToSpeech.SUCCESS) throw IllegalStateException("Text-to-speech refused the text")
                    withTimeout(120_000) { done.await() }
                }
                val joined = WavJoin.join(tmp.map { it.readBytes() })
                val file = unique(dir(context), clean(name) + ".wav")
                file.writeBytes(joined)
                return file
            } finally {
                tmp.forEach { it.delete() }
            }
        } finally {
            withContext(Dispatchers.Main) { tts.shutdown() }
        }
    }
}
