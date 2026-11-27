package com.gravity.ide.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measures a design picture (a screenshot or mockup) for phone models, which cannot see pictures:
 * the background and its gradient, photos, cards and buttons, bars (sliders, progress), icons
 * with a guess of common shapes (pause, play, next, list…), and every text with its size, color
 * and weight, top to bottom in CSS pixels for a phone 390 px wide. Browser and phone status bars
 * are left out, and so is text the OCR read inside a photo (part of the photo, not of the app)
 * or out of an icon. These are measurements, not a view of the picture: names of icon shapes are
 * guesses and say "looks like".
 *
 * Pure Kotlin (pixels and OCR lines in, text out), so it is tested on the computer; ImageText
 * gives it the phone's pixels and ML Kit's lines.
 */
object DesignReader {
    /** A line of text found by OCR, in picture pixels; [confidence] 0..1 when the OCR gives one. */
    data class TextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float? = null) {
        val height get() = bottom - top
        val width get() = right - left
    }

    /** The picture as ARGB pixels, row after row. */
    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    const val PHONE_WIDTH = 390

    /** How far (RGB distance) a pixel must be from the background around it to be part of something. */
    private const val FG = 16
    const val MAX_CHARS = 2400

    // ---------------------------------------------------------------- colors

    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun dist(a: Int, c: Int): Int {
        val dr = r(a) - r(c)
        val dg = g(a) - g(c)
        val db = b(a) - b(c)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt()
    }
    fun hex(c: Int) = String.format(java.util.Locale.ROOT, "#%02x%02x%02x", r(c), g(c), b(c))
    private fun luma(c: Int) = (299 * r(c) + 587 * g(c) + 114 * b(c)) / 1000

    /** Transparent pixels (a window's rounded corners) take the nearest solid color of their row. */
    private fun solid(px: Pixels): IntArray {
        val out = px.argb.copyOf()
        val w = px.width
        for (y in 0 until px.height) {
            val o = y * w
            var have = false
            var last = 0
            for (x in 0 until w) {
                val a = (out[o + x] ushr 24) and 255
                if (a >= 128) {
                    val c = out[o + x] or (0xFF shl 24)
                    out[o + x] = c
                    if (!have) for (i in 0 until x) out[o + i] = c
                    have = true
                    last = c
                } else if (have) {
                    out[o + x] = last
                }
            }
            if (!have) for (x in 0 until w) out[o + x] = if (y > 0) out[o - w + x] else 0xFFFFFFFF.toInt()
        }
        return out
    }

    private fun median(values: IntArray, n: Int): Int {
        if (n <= 0) return 0
        val s = values.copyOf(n)
        s.sort()
        return s[n / 2]
    }

    /** The middle color of a row (per channel). */
    private fun rowColor(p: IntArray, w: Int, y: Int, x0: Int = 0, x1: Int = w): Int {
        val n = (x1 - x0 + 1) / 2
        val rs = IntArray(n)
        val gs = IntArray(n)
        val bs = IntArray(n)
        var k = 0
        var x = x0
        while (x < x1 && k < n) {
            val c = p[y * w + x]
            rs[k] = r(c); gs[k] = g(c); bs[k] = b(c)
            k++
            x += 2
        }
        return rgb(median(rs, k), median(gs, k), median(bs, k))
    }

    // ---------------------------------------------------------------- what is not the app

    private val urlRe = Regex("^(https?://)?([a-z0-9-]+\\.)+[a-z]{2,6}(/\\S*)?$", RegexOption.IGNORE_CASE)
    private val statusRe = Regex(
        "^(\\d{1,2}[:.]\\d{2}(\\s?[ap]m)?|\\d{1,3}\\s?%|[2-5]g\\+?|lte|vo\\s?lte\\d?|wi-?fi|[\\d.]+\\s?[kmg]b/s)$",
        RegexOption.IGNORE_CASE,
    )

    /** Where the app's own screen starts: below a phone status bar or a browser's address bar. */
    internal fun contentTop(p: IntArray, w: Int, h: Int, lines: List<TextLine>): Int {
        var top = 0
        val status = lines.filter { l -> l.bottom <= h * 0.06 && l.text.trim().split(Regex("\\s+")).all { statusRe.matches(it) } }
        if (status.isNotEmpty()) top = status.maxOf { it.bottom } + status.maxOf { it.height } / 2
        // An address bar: a web address that is most of its line (OCR may add the lock or sound icon as "a" or "4)").
        val url = lines.firstOrNull { l ->
            val text = l.text.trim()
            l.top < h * 0.15 && text.split(Regex("\\s+")).any { tok -> urlRe.matches(tok.trim('/')) && tok.length * 2 >= text.length }
        }
        if (url != null) {
            val y0 = min(h - 1, url.bottom + 2)
            val bar = rowColor(p, w, y0)
            val limit = min(h - 5, (h * 0.25).toInt())
            var end = -1
            var y = y0
            while (y < limit) {
                if ((0..3).all { dist(rowColor(p, w, y + it), bar) > 40 }) {
                    end = y
                    break
                }
                y++
            }
            top = max(top, if (end > 0) end + 2 else url.bottom + url.height / 2)
        }
        return top.coerceIn(0, h / 3)
    }

    private val shortWord = Regex("^\\p{L}{2,3}$")
    private val number = Regex("^[-+]?\\d+([:.,]\\d+)*%?$")

    /** OCR "text" that is really an icon or noise: "4)", "<<", "il" read from a pause icon… */
    internal fun isJunk(line: TextLine, typicalHeight: Int): Boolean {
        val t = line.text.trim()
        if (line.confidence != null && line.confidence < 0.45f) return true
        if (t.count { it.isLetterOrDigit() } == 0) return true
        if (t.length <= 3 && !number.matches(t)) {
            if (typicalHeight > 0 && line.height > typicalHeight * 1.5) return true
            if (!shortWord.matches(t)) return true
        }
        return false
    }

    // ---------------------------------------------------------------- the measured parts

    private sealed class El(var l: Int, var t: Int, var r: Int, var b: Int) {
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        val children = mutableListOf<El>()
    }

    private class TextEl(val text: String, val em: Float, val color: Int, val bold: Boolean, l: Int, t: Int, r: Int, b: Int) : El(l, t, r, b)
    private class PictureEl(val colors: List<Int>, val radius: Int, l: Int, t: Int, r: Int, b: Int) : El(l, t, r, b) {
        var hasText = false
    }
    private class BoxEl(val color: Int, val radius: Int, val outlined: Boolean, l: Int, t: Int, r: Int, b: Int) : El(l, t, r, b)
    private class BarEl(val first: Int, val rest: Int?, val fraction: Float?, val thick: Int, val knob: Float?, val rounded: Boolean, l: Int, t: Int, r: Int, b: Int) : El(l, t, r, b)
    private class IconEl(val color: Int, val shape: String?, val round: Boolean, val inner: Int?, val innerShape: String?, l: Int, t: Int, r: Int, b: Int) : El(l, t, r, b)
    private class GraphicEl(val colors: List<Int>, l: Int, t: Int, r: Int, b: Int) : El(l, t, r, b)

    /** The measured description, or "" when the picture is empty or too small to measure. */
    fun describe(px: Pixels, ocr: List<TextLine>): String {
        val w = px.width
        val h = px.height
        if (w < 40 || h < 40 || px.argb.size < w * h) return ""
        val p = solid(px)
        val top = contentTop(p, w, h, ocr)
        val ch = h - top
        val cw = w
        val scale = PHONE_WIDTH.toFloat() / cw

        // Text lines of the app (not of the bars above it), junk left out.
        val inContent = ocr.filter { it.top >= top - 2 && it.width > 0 && it.height > 0 }
        val longOnes = inContent.filter { it.text.trim().length >= 4 }.map { it.height }.toIntArray()
        val typical = if (longOnes.isNotEmpty()) median(longOnes, longOnes.size) else median(inContent.map { it.height }.toIntArray(), inContent.size)
        val lines = inContent.filter { !isJunk(it, typical) }

        // A grid of small cells: average color and how much it varies inside.
        val cell = max(3, (cw / 64.0).roundToInt())
        val cols = (cw + cell - 1) / cell
        val rows = (ch + cell - 1) / cell
        val n = cols * rows
        val mean = IntArray(n)
        val spread = FloatArray(n)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            var sr = 0L; var sg = 0L; var sb = 0L; var sq = 0L; var cnt = 0
            val ys = top + cy * cell
            val xs = cx * cell
            for (y in ys until min(h, ys + cell)) for (x in xs until min(w, xs + cell)) {
                val c = p[y * w + x]
                val rr = r(c); val gg = g(c); val bb = b(c)
                sr += rr; sg += gg; sb += bb
                sq += (rr * rr + gg * gg + bb * bb).toLong()
                cnt++
            }
            if (cnt == 0) continue
            val mr = sr.toDouble() / cnt; val mg = sg.toDouble() / cnt; val mb = sb.toDouble() / cnt
            mean[cy * cols + cx] = rgb(mr.roundToInt(), mg.roundToInt(), mb.roundToInt())
            spread[cy * cols + cx] = sqrt(max(0.0, sq.toDouble() / cnt - (mr * mr + mg * mg + mb * mb))).toFloat()
        }

        // The background: smooth cells reachable from the edges in small color steps (gradients too).
        // Seeds: the outer two rings of cells (the outer one may hold a window's frame line).
        val border = ArrayList<Int>()
        for (ring in 0..1) {
            if (cols <= 2 * ring || rows <= 2 * ring) break
            for (cx in ring until cols - ring) { border += ring * cols + cx; border += (rows - 1 - ring) * cols + cx }
            for (cy in ring until rows - ring) { border += cy * cols + ring; border += cy * cols + cols - 1 - ring }
        }
        val borderSpread = border.map { (spread[it] * 10).toInt() }.toIntArray()
        val flat = (median(borderSpread, borderSpread.size) / 10f * 2.5f + 4f).coerceIn(6f, 20f)
        val isBg = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        for (i in border) if (!isBg[i] && spread[i] < flat) { isBg[i] = true; queue.add(i) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val cx = i % cols
            val cy = i / cols
            for ((dx, dy) in NEIGHBORS4) {
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue
                val j = y * cols + x
                if (isBg[j] || spread[j] >= flat || dist(mean[j], mean[i]) > 14) continue
                isBg[j] = true
                queue.add(j)
            }
        }
        // Every cell's local background: the nearest background cell's color.
        val bgOf = IntArray(n)
        val known = BooleanArray(n)
        val fill = ArrayDeque<Int>()
        for (i in 0 until n) if (isBg[i]) { bgOf[i] = mean[i]; known[i] = true; fill.add(i) }
        if (fill.isEmpty()) {
            val all = mean.copyOf()
            val fallback = rgb(median(IntArray(n) { r(all[it]) }, n), median(IntArray(n) { g(all[it]) }, n), median(IntArray(n) { b(all[it]) }, n))
            for (i in 0 until n) bgOf[i] = fallback
        } else {
            while (fill.isNotEmpty()) {
                val i = fill.removeFirst()
                val cx = i % cols
                val cy = i / cols
                for ((dx, dy) in NEIGHBORS4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue
                    val j = y * cols + x
                    if (known[j]) continue
                    known[j] = true
                    bgOf[j] = bgOf[i]
                    fill.add(j)
                }
            }
        }
        // Background enclosed by something (the inside of an outlined field or card): smooth cells in the
        // background's own color count as background too.
        for (i in 0 until n) if (!isBg[i] && known[i] && spread[i] < flat && dist(mean[i], bgOf[i]) <= 10) isBg[i] = true
        fun bgAt(x: Int, y: Int): Int = bgOf[(((y - top) / cell).coerceIn(0, rows - 1)) * cols + (x / cell).coerceIn(0, cols - 1)]

        // Which cells hold text (the OCR's lines, a little wider).
        val inText = BooleanArray(n)
        for (l in lines) {
            val pad = cell / 2
            for (cy in max(0, (l.top - top - pad) / cell)..min(rows - 1, (l.bottom - top + pad) / cell))
                for (cx in max(0, (l.left - pad) / cell)..min(cols - 1, (l.right + pad) / cell)) {
                    val mx = cx * cell + cell / 2
                    val my = top + cy * cell + cell / 2
                    if (mx >= l.left - pad && mx <= l.right + pad && my >= l.top - pad && my <= l.bottom + pad) inText[cy * cols + cx] = true
                }
        }

        // The parts: groups of touching cells that are not background.
        val seen = BooleanArray(n)
        val parts = mutableListOf<El>()
        for (start in 0 until n) {
            if (isBg[start] || seen[start]) continue
            val group = ArrayList<Int>()
            seen[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                group += i
                val cx = i % cols
                val cy = i / cols
                for (dy in -1..1) for (dx in -1..1) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue
                    val j = y * cols + x
                    if (isBg[j] || seen[j]) continue
                    seen[j] = true
                    queue.add(j)
                }
            }
            measure(group, p, w, h, top, cell, cols, cw, spread, inText, lines, ::bgAt)?.let { parts += it }
        }

        mergeIcons(parts, p, w, cw, ::bgAt)
        // Specks too small to be anything.
        parts.removeAll { it is IconEl && max(it.w, it.h) < max(3, cw / 90) }
        // Texts: color, size and weight measured in the picture.
        val texts = lines.map { l -> textOf(l, p, w, h) }
        // A text inside a photo belongs to the photo; inside a card or button, to that card.
        val pictures = parts.filterIsInstance<PictureEl>()
        val bigPicture = pictures.firstOrNull { it.w * it.h > cw * ch * 0.6 }
        val topLevel = mutableListOf<El>()
        for (t in texts) {
            val pic = pictures.firstOrNull { it !== bigPicture && inside(t, it, 0.6f) }
            if (pic != null) { pic.hasText = true; continue }
            val box = parts.filterIsInstance<BoxEl>().filter { inside(t, it, 0.8f) }.minByOrNull { it.w * it.h }
            if (box != null) box.children += t else topLevel += t
        }
        for (part in parts) {
            if (part === bigPicture) continue
            if (part !is BoxEl && part !is PictureEl) {
                val box = parts.filterIsInstance<BoxEl>().filter { it !== part && inside(part, it, 0.8f) }.minByOrNull { it.w * it.h }
                if (box != null) { box.children += part; continue }
            }
            if (part is BarEl && part.t > top + ch * 0.97 && part.w < cw * 0.45) continue // the phone's gesture bar
            topLevel += part
        }

        // Rows: parts side by side (overlapping in height, not in width), top to bottom.
        val rowsOut = mutableListOf<MutableList<El>>()
        for (e in topLevel.sortedWith(compareBy({ it.t }, { it.l }))) {
            val row = rowsOut.lastOrNull { row ->
                row.any { o -> overlapV(e, o) >= 0.5f * min(e.h, o.h) } && row.none { o -> overlapH(e, o) > 0 }
            }
            if (row != null && rowsOut.indexOf(row) >= rowsOut.size - 2) row += e else rowsOut += mutableListOf(e)
        }

        // Text sizes: on a phone, body text is at least about 15 px; small screenshots of a big window are enlarged.
        val ems = texts.map { it.em * scale }.sorted()
        val bodyEm = if (ems.isEmpty()) 15f else ems[ems.size / 2]
        val textBoost = if (bodyEm < 13f) (15f / bodyEm).coerceAtMost(2.2f) else 1f
        fun font(t: TextEl) = max(11, (t.em * scale * textBoost).roundToInt())
        fun px(v: Int) = max(1, (v * scale).roundToInt())
        fun pct(v: Int) = (v * 100f / cw).roundToInt()

        val bgColors = cornerColors(p, w, h, top, isBg, mean, cols, rows, cell)
        val out = StringBuilder()
        val dark = luma(bgColors.avgColor()) < 110
        out.append("Screen: ${if (dark) "dark" else "light"}, about ${px(ch)} px tall at $PHONE_WIDTH px wide.")
        if (top > 0) out.append(" (The browser or phone bar above the app was left out.)")
        out.append("\nBackground: ").append(
            if (bigPicture != null) "a full-screen picture, mostly ${(bigPicture as PictureEl).colors.joinToString(", ") { hex(it) }}"
            else backgroundCss(bgColors),
        ).append('\n')

        fun describeEl(e: El): String = when (e) {
            is TextEl -> "text \"${e.text}\" ${font(e)} px${if (e.bold) " bold" else ""} ${hex(e.color)}"
            is PictureEl -> "picture ${px(e.w)}×${px(e.h)} px (${pct(e.w)}% wide)" +
                (if (e.radius > 0) ", rounded ${px(e.radius)} px" else "") +
                ", a photo, mostly ${e.colors.joinToString(", ") { hex(it) }}" +
                if (e.hasText) "; the words printed on it belong to the photo" else ""
            is BoxEl -> (if (e.outlined) "outlined box (border ${hex(e.color)})" else "box ${hex(e.color)}") + " ${px(e.w)}×${px(e.h)} px (${pct(e.w)}% wide)" +
                (if (e.radius > 0) if (e.radius * 2 >= e.h - 2) ", pill-shaped" else ", rounded ${px(e.radius)} px" else "") +
                if (e.children.isEmpty()) "" else ", with " + e.children.sortedWith(compareBy({ it.t / max(1, cell) }, { it.l }))
                    .joinToString(" and ") { c -> describeEl(c) + where(c, e) } + " inside"
            is BarEl -> "bar ${px(e.thick)} px thick, ${pct(e.w)}% wide" + (if (e.rounded) ", rounded ends" else "") +
                if (e.rest != null && e.fraction != null) ": ${hex(e.first)} from the left to ${(e.fraction * 100).roundToInt()}%, then ${hex(e.rest)} (a progress bar or slider)"
                else ": ${hex(e.first)}" + (e.knob?.let { ", with a round knob at ${(it * 100).roundToInt()}% (a slider)" } ?: if (px(e.thick) <= 2) " (a divider line)" else "")
            is IconEl -> if (e.round) "round button ${px(max(e.w, e.h))} px ${hex(e.color)}" +
                (if (e.inner != null) " with a ${hex(e.inner)} icon inside" + (e.innerShape?.let { " that looks like $it" } ?: "") else "")
            else "icon ${px(max(e.w, e.h))} px ${hex(e.color)}" + (e.shape?.let { " that looks like $it" } ?: "")
            is GraphicEl -> "graphic ${px(e.w)}×${px(e.h)} px (${pct(e.w)}% wide), colors ${e.colors.joinToString(", ") { hex(it) }}"
        }

        out.append("Top to bottom (sizes in CSS px; x = horizontal place in % of the width):\n")
        var lastBottom = top
        var written = 0
        for ((i, row) in rowsOut.withIndex()) {
            row.sortBy { it.l }
            val rowTop = row.minOf { it.t }
            val gap = px(max(0, rowTop - lastBottom))
            val line = StringBuilder("${i + 1}. ")
            if (gap >= 4) line.append("[gap $gap] ")
            if (row.size == 1) {
                val e = row[0]
                line.append(describeEl(e)).append(placeOf(e, cw))
            } else {
                val spreadOut = row.first().l < cw * 0.15 && row.last().r > cw * 0.85
                line.append("${row.size} side by side${if (spreadOut) " across the width" else ""}: ")
                line.append(row.joinToString("; ") { e -> describeEl(e) + " at x ${pct(e.cx)}%" })
            }
            lastBottom = max(lastBottom, row.maxOf { it.b })
            if (out.length + line.length > MAX_CHARS - 40) {
                out.append("… and ${rowsOut.size - written} more rows.\n")
                break
            }
            out.append(line).append('\n')
            written++
        }
        if (rowsOut.isEmpty()) out.append("(no separate parts found)\n")
        return out.toString().trim()
    }

    private fun List<Int>.avgColor(): Int = if (isEmpty()) 0xFF808080.toInt() else rgb(sumOf { r(it) } / size, sumOf { g(it) } / size, sumOf { b(it) } / size)

    private fun where(c: El, box: El): String {
        val mid = (c.cx - box.l).toFloat() / max(1, box.w)
        return when {
            mid in 0.4f..0.6f -> " centered"
            mid < 0.4f -> " at the left"
            else -> " at the right"
        }
    }

    private fun placeOf(e: El, cw: Int): String {
        val pctL = (e.l * 100f / cw).roundToInt()
        val pctR = ((cw - e.r) * 100f / cw).roundToInt()
        return when {
            e.w > cw * 0.9 -> ", full width"
            abs(e.cx - cw / 2) < cw * 0.06 -> ", centered"
            e.cx < cw / 2 -> ", left (x $pctL%)"
            else -> ", right ($pctR% from the right edge)"
        }
    }

    private fun overlapV(a: El, b: El) = max(0, min(a.b, b.b) - max(a.t, b.t))
    private fun overlapH(a: El, b: El) = max(0, min(a.r, b.r) - max(a.l, b.l))
    private fun inside(a: El, outer: El, part: Float): Boolean {
        val area = max(1, a.w * a.h)
        return overlapV(a, outer).toLong() * overlapH(a, outer) >= area * part
    }

    private val NEIGHBORS4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** The background colors near the four corners (top-left, top-right, bottom-left, bottom-right) and the middle. */
    private fun cornerColors(p: IntArray, w: Int, h: Int, top: Int, isBg: BooleanArray, mean: IntArray, cols: Int, rows: Int, cell: Int): List<Int> {
        fun area(cx0: Int, cx1: Int, cy0: Int, cy1: Int): Int? {
            val cs = mutableListOf<Int>()
            for (cy in cy0..cy1) for (cx in cx0..cx1) {
                val i = cy.coerceIn(0, rows - 1) * cols + cx.coerceIn(0, cols - 1)
                if (isBg[i]) cs += mean[i]
            }
            if (cs.isEmpty()) return null
            return rgb(median(cs.map { r(it) }.toIntArray(), cs.size), median(cs.map { g(it) }.toIntArray(), cs.size), median(cs.map { b(it) }.toIntArray(), cs.size))
        }
        val qx = max(1, cols / 5)
        val qy = max(1, rows / 6)
        val tl = area(0, qx, 0, qy)
        val tr = area(cols - 1 - qx, cols - 1, 0, qy)
        val bl = area(0, qx, rows - 1 - qy, rows - 1)
        val br = area(cols - 1 - qx, cols - 1, rows - 1 - qy, rows - 1)
        val mid = area(0, cols - 1, rows / 2 - qy / 2, rows / 2 + qy / 2)
        val any = listOfNotNull(tl, tr, bl, br, mid).ifEmpty { listOf(rowColor(p, w, min(h - 1, top + (h - top) / 2))) }
        val fallback = any.avgColor()
        return listOf(tl ?: fallback, tr ?: fallback, bl ?: fallback, br ?: fallback, mid ?: fallback)
    }

    /** One color, or a CSS gradient that runs the way the colors change most. */
    internal fun backgroundCss(c: List<Int>): String {
        val (tl, tr, bl, br) = c
        val mid = c.getOrElse(4) { tl }
        val spreadAll = listOf(tl, tr, bl, br, mid).let { cs -> cs.maxOf { a -> cs.maxOf { dist(a, it) } } }
        if (spreadAll < 24) return "${hex(listOf(tl, tr, bl, br, mid).avgColor())} (one color)"
        val vertical = (dist(tl, bl) + dist(tr, br)) / 2
        val horizontal = (dist(tl, tr) + dist(bl, br)) / 2
        val diagonal = dist(tl, br)
        val anti = dist(tr, bl)
        return when {
            diagonal >= max(vertical, horizontal) * 1.15 && diagonal >= anti -> "linear-gradient(135deg, ${hex(tl)}, ${hex(mid)}, ${hex(br)})"
            anti >= max(vertical, horizontal) * 1.15 -> "linear-gradient(225deg, ${hex(tr)}, ${hex(mid)}, ${hex(bl)})"
            vertical >= horizontal -> "linear-gradient(180deg, ${hex(listOf(tl, tr).avgColor())}, ${hex(mid)}, ${hex(listOf(bl, br).avgColor())})"
            else -> "linear-gradient(90deg, ${hex(listOf(tl, bl).avgColor())}, ${hex(mid)}, ${hex(listOf(tr, br).avgColor())})"
        } + " (corners: top-left ${hex(tl)}, top-right ${hex(tr)}, bottom-left ${hex(bl)}, bottom-right ${hex(br)})"
    }

    // ---------------------------------------------------------------- one part

    private fun measure(
        group: List<Int>, p: IntArray, w: Int, h: Int, top: Int, cell: Int, cols: Int, cw: Int,
        spread: FloatArray, inText: BooleanArray, lines: List<TextLine>, bgAt: (Int, Int) -> Int,
    ): El? {
        // The exact edges: pixels that differ from the background around them.
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (i in group) {
            val x0 = (i % cols) * cell
            val y0 = top + (i / cols) * cell
            val bg = bgAt(x0, y0)
            for (y in y0 until min(h, y0 + cell)) for (x in x0 until min(w, x0 + cell)) {
                if (dist(p[y * w + x], bg) > FG) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < 0) return null
        r += 1; b += 1
        val ew = r - l
        val eh = b - t
        if (ew < 2 && eh < 2) return null
        // Texts whose middle is inside this part.
        val texts = lines.filter { (it.left + it.right) / 2 in l until r && (it.top + it.bottom) / 2 in t until b }
        val textCells = group.count { inText[it] }
        if (textCells >= group.size * 0.5f) {
            // Lines of text (the OCR describes them), unless the space around the words is filled too (a button).
            var all = 0
            var outside = 0
            var filledOutside = 0
            val stepPx = max(1, min(ew, eh) / 40)
            for (y in t until b step stepPx) for (x in l until r step stepPx) {
                all++
                if (lines.any { x >= it.left - 2 && x <= it.right + 2 && y >= it.top - 2 && y <= it.bottom + 2 }) continue
                outside++
                if (dist(p[y * w + x], bgAt(x, y)) > FG) filledOutside++
            }
            if (outside < all * 0.35f || filledOutside < outside * 0.6f) return null
        }

        // Pixels that differ from the background inside the edges.
        val bg = bgAt(l + ew / 2, t + eh / 2)
        val mask = BooleanArray(ew * eh)
        var fg = 0
        var sr = 0L; var sg = 0L; var sb = 0L
        for (y in t until b) for (x in l until r) {
            val c = p[y * w + x]
            if (dist(c, bgAt(x, y)) > FG) {
                mask[(y - t) * ew + (x - l)] = true
                fg++
                sr += r(c); sg += g(c); sb += b(c)
            }
        }
        if (fg == 0) return null
        val fgColor = rgb((sr / fg).toInt(), (sg / fg).toInt(), (sb / fg).toInt())
        val filled = fg.toFloat() / (ew * eh)

        // A bar: long and thin (a slider, a progress bar, a divider).
        val colCount = IntArray(ew) { x -> var k = 0; for (y in 0 until eh) if (mask[y * ew + x]) k++; k }
        val nonEmpty = colCount.filter { it > 0 }.toIntArray()
        val thick = if (nonEmpty.isEmpty()) eh else median(nonEmpty, nonEmpty.size)
        if (ew >= cw * 0.18 && thick <= max(2f, cw * 0.03f) && ew >= thick * 8 && nonEmpty.size >= ew * 0.85) {
            // One band across (with at most a knob above and below it), not the top and bottom edges of a box.
            val rowCount = IntArray(eh) { y -> var k = 0; for (x in 0 until ew) if (mask[y * ew + x]) k++; k }
            val band = runs(rowCount, (ew * 0.6f).toInt())
            if (band.size == 1 && band[0].count() <= max(3f, cw * 0.035f) && rowCount.indices.none { it !in band[0] && rowCount[it] > ew * 0.2f }) {
                return barOf(p, w, l, t, ew, eh, thick, colCount, bgAt)
            }
        }

        val nonTextSpread = group.filter { !inText[it] }.map { spread[it] }
        val avgSpread = if (nonTextSpread.isEmpty()) 0f else nonTextSpread.average().toFloat()
        val minCx = group.minOf { it % cols }
        val minCy = group.minOf { it / cols }
        val cellsW = group.maxOf { it % cols } - minCx + 1
        val cellsH = group.maxOf { it / cols } - minCy + 1
        val cellFill = group.size.toFloat() / (cellsW * cellsH)

        if (ew >= cw * 0.15 && eh >= cw * 0.08) {
            val radius = radiusOf(p, w, l, t, r, b, bgAt)
            if (cellFill >= 0.55f && filled >= 0.5f) {
                // A photo has many colors; a card or button is mostly one color (with words or icons on it).
                val flatBox = avgSpread < 12f || mainShare(p, w, l, t, r, b) >= 0.55f
                return if (!flatBox) PictureEl(dominant(p, w, l, t, r, b), radius, l, t, r, b)
                else BoxEl(boxColor(group, p, w, h, top, cell, cols, inText, fgColor), radius, false, l, t, r, b)
            }
            // A ring of cells along the edges: an outlined box (an input field, a card with a border).
            val edgeCells = group.count { i ->
                val cx = i % cols - minCx
                val cy = i / cols - minCy
                cx <= 1 || cy <= 1 || cx >= cellsW - 2 || cy >= cellsH - 2
            }
            if (edgeCells >= group.size * 0.75f && cellsW >= 6 && cellsH >= 5) {
                // The frame of the window or screen itself is not part of the design.
                if (ew >= cw * 0.95f && eh >= (h - top) * 0.9f) return null
                return BoxEl(fgColor, radius, true, l, t, r, b)
            }
            return GraphicEl(dominant(p, w, l, t, r, b), l, t, r, b)
        }
        if (texts.isNotEmpty() && filled >= 0.5f) {
            // A small box with words on it: a chip or a button.
            return BoxEl(boxColor(group, p, w, h, top, cell, cols, inText, fgColor), radiusOf(p, w, l, t, r, b, bgAt), false, l, t, r, b)
        }
        if (max(ew, eh) <= cw * 0.2) {
            // A filled circle in one color: a round button, maybe with an icon inside.
            val aspect = ew.toFloat() / eh
            if (aspect in 0.8f..1.25f && filled in 0.6f..0.9f && min(ew, eh) >= cw * 0.025f) {
                val main = mainColor(p, w, l, t, r, b, mask, ew)
                val close = countNear(p, w, l, t, r, b, mask, ew, main, 34)
                if (close >= fg * 0.55f) {
                    val inner = BooleanArray(ew * eh)
                    var ir = 0L; var ig = 0L; var ib = 0L; var ink = 0
                    for (y in t until b) for (x in l until r) {
                        val k = (y - t) * ew + (x - l)
                        val c = p[y * w + x]
                        if (mask[k] && dist(c, main) > 45 && insideCircle(x - l, y - t, ew, eh, 0.8f)) {
                            inner[k] = true
                            ink++
                            ir += r(c); ig += g(c); ib += b(c)
                        }
                    }
                    // Holes in the circle (the icon in the background's color) count as the icon too.
                    for (y in t until b) for (x in l until r) {
                        val k = (y - t) * ew + (x - l)
                        if (!mask[k] && insideCircle(x - l, y - t, ew, eh, 0.7f)) {
                            inner[k] = true
                            ink++
                            val c = p[y * w + x]
                            ir += r(c); ig += g(c); ib += b(c)
                        }
                    }
                    val innerColor = if (ink >= max(4, (ew * eh * 0.03f).toInt())) rgb((ir / ink).toInt(), (ig / ink).toInt(), (ib / ink).toInt())
                        .takeIf { dist(it, main) > 30 } else null
                    return IconEl(main, null, true, innerColor, if (innerColor != null) shapeOf(crop(inner, ew, eh)) else null, l, t, r, b)
                }
            }
            val strong = strongMask(p, w, l, t, r, b, bgAt)
            return IconEl(mainColor(p, w, l, t, r, b, mask, ew), shapeOf(crop(strong, ew, eh)), false, null, null, l, t, r, b)
        }
        return GraphicEl(dominant(p, w, l, t, r, b), l, t, r, b)
    }

    /**
     * Pieces of one icon drawn apart (the two bars of ⏸, the three dots of ⋯) become one icon: thin or
     * tiny pieces of one color, side by side and close together.
     */
    private fun mergeIcons(parts: MutableList<El>, p: IntArray, w: Int, cw: Int, bgAt: (Int, Int) -> Int) {
        fun piece(e: El) = e is IconEl && !e.round && (e.w <= e.h * 0.6f || max(e.w, e.h) <= cw * 0.04f)
        var merged = true
        while (merged) {
            merged = false
            val icons = parts.filter { piece(it) }.sortedBy { it.l }
            loop@ for (a in icons) for (b in icons) {
                if (a === b || b.l < a.l) continue
                a as IconEl; b as IconEl
                val gap = b.l - a.r
                val sameLine = overlapV(a, b) >= 0.6f * min(a.h, b.h) || abs(a.cy - b.cy) <= max(a.h, b.h) / 2
                if (gap < 0 || gap > max(a.h, b.h) * 0.8f || gap > cw * 0.06f || !sameLine || dist(a.color, b.color) > 40) continue
                val l = min(a.l, b.l); val t = min(a.t, b.t); val r = max(a.r, b.r); val bb = max(a.b, b.b)
                if (max(r - l, bb - t) > cw * 0.2f) continue
                val shape = shapeOf(crop(strongMask(p, w, l, t, r, bb, bgAt), r - l, bb - t))
                parts.remove(a); parts.remove(b)
                parts += IconEl(a.color, shape, false, null, null, l, t, r, bb)
                merged = true
                break@loop
            }
        }
    }

    private fun insideCircle(x: Int, y: Int, w: Int, h: Int, part: Float): Boolean {
        val dx = (x + 0.5f - w / 2f) / (w / 2f)
        val dy = (y + 0.5f - h / 2f) / (h / 2f)
        return dx * dx + dy * dy <= part * part
    }

    /** Pixels clearly apart from the background (half-way to the strongest difference): the icon's own lines. */
    private fun strongMask(p: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int, bgAt: (Int, Int) -> Int): BooleanArray {
        val ew = r - l
        val eh = b - t
        var maxD = 0
        for (y in t until b) for (x in l until r) maxD = max(maxD, dist(p[y * w + x], bgAt(x, y)))
        val cut = max(40, maxD / 2)
        return BooleanArray(ew * eh) { k -> dist(p[(t + k / ew) * w + l + k % ew], bgAt(l + k % ew, t + k / ew)) > cut }
    }

    /** The mask cut down to the box around its set pixels, with its size. */
    private class Mask(val bits: BooleanArray, val w: Int, val h: Int)

    private fun crop(m: BooleanArray, w: Int, h: Int): Mask {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) {
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
        }
        if (r < 0) return Mask(BooleanArray(0), 0, 0)
        val nw = r - l + 1
        val nh = b - t + 1
        return Mask(BooleanArray(nw * nh) { k -> m[(t + k / nw) * w + l + k % nw] }, nw, nh)
    }

    private fun runs(profile: IntArray, min: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in profile.indices) {
            if (profile[i] >= min) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out += start until i
                start = -1
            }
        }
        if (start >= 0) out += start until profile.size
        return out
    }

    /** A guess of a common icon from its shape, or null: only shapes that are clear are named. */
    private fun shapeOf(m: Mask): String? {
        val w = m.w
        val h = m.h
        if (w < 4 || h < 4) return null
        val col = IntArray(w) { x -> var k = 0; for (y in 0 until h) if (m.bits[y * w + x]) k++; k }
        val row = IntArray(h) { y -> var k = 0; for (x in 0 until w) if (m.bits[y * w + x]) k++; k }
        val colRuns = runs(col, max(1, (h * 0.12f).toInt()))
        val rowRuns = runs(row, max(1, (w * 0.12f).toInt()))
        val fill = m.bits.count { it }.toFloat() / (w * h)
        if (rowRuns.size == 1 && colRuns.size == 3 && w >= h * 1.6f) return "⋯ (more)"
        if (colRuns.size == 1 && rowRuns.size == 3 && h >= w * 1.6f) return "⋮ (more)"
        if (rowRuns.size == 3 && w >= h * 0.8f && rowRuns.all { it.count() <= h * 0.3f }) return "☰ (a list or menu)"
        if (rowRuns.size == 1 && colRuns.size == 2 && fill >= 0.45f && colRuns.all { rr -> rr.count() <= w * 0.45f && rr.map { col[it] }.average() >= h * 0.75f }) {
            return "⏸ (pause)"
        }
        if (rowRuns.size != 1) return null
        // Triangles and bars from left to right: split where the column height jumps or stops.
        val segments = mutableListOf<IntRange>()
        val minOn = max(1, (h * 0.08f).toInt())
        var s = -1
        for (x in 0 until w) {
            if (col[x] >= minOn) {
                if (s < 0) s = x
                val jumpNext = x + 1 < w && col[x + 1] >= minOn && abs(col[x + 1] - col[x]) >= h * 0.45f
                if (jumpNext || x == w - 1) {
                    segments += s..x
                    s = -1
                }
            } else if (s >= 0) {
                segments += s until x
                s = -1
            }
        }
        val kinds = segments.filter { it.count() >= max(2, w / 12) }.map { seg ->
            val third = max(1, seg.count() / 3)
            val first = seg.take(third).map { col[it] }.average()
            val last = seg.toList().takeLast(third).map { col[it] }.average()
            when {
                first >= h * 0.55f && last <= first * 0.6f -> 'F' // high to low: points right
                last >= h * 0.55f && first <= last * 0.6f -> 'R' // low to high: points left
                first >= h * 0.7f && last >= h * 0.7f && seg.count() <= h * 0.45f -> 'B' // a bar
                else -> '?'
            }
        }.joinToString("")
        return when (kinds) {
            "F" -> "▶ (play)"
            "R" -> "◀ (a triangle pointing left)"
            "FF" -> "⏩ (forward / next)"
            "RR" -> "⏪ (rewind / previous)"
            "FB", "FFB" -> "⏭ (next)"
            "BR", "BRR" -> "⏮ (previous)"
            "BB" -> "⏸ (pause)"
            else -> null
        }
    }

    private fun barOf(p: IntArray, w: Int, l: Int, t: Int, ew: Int, eh: Int, thick: Int, colCount: IntArray, bgAt: (Int, Int) -> Int): BarEl {
        // The middle row of the bar's band, read from left to right: one color, or two (filled and not).
        var bandMid = t + eh / 2
        run {
            var best = -1
            for (y in t until t + eh) {
                var k = 0
                for (x in l until l + ew) if (dist(p[y * w + x], bgAt(x, y)) > FG) k++
                if (k > best) { best = k; bandMid = y }
            }
        }
        val colors = IntArray(ew) { p[bandMid * w + l + it] }
        val segs = mutableListOf<Pair<Int, Int>>() // start, color
        var segStart = 0
        var ref = colors[0]
        for (x in 1 until ew) {
            if (dist(colors[x], ref) > 34 && (x + 2 >= ew || dist(colors[min(ew - 1, x + 2)], ref) > 34)) {
                segs += segStart to ref
                segStart = x
                ref = colors[min(ew - 1, x + 2)]
            }
        }
        segs += segStart to ref
        val long = segs.mapIndexed { i, (st, c) -> Triple(st, (segs.getOrNull(i + 1)?.first ?: ew) - st, c) }.filter { it.second >= max(3, ew / 40) }
        val knobAt = colCount.indices.filter { colCount[it] >= thick * 2.2f }.let { k -> if (k.isEmpty() || k.size > ew * 0.12) null else k.average().toFloat() / ew }
        val rounded = colCount.first() < thick * 0.7f || colCount.last() < thick * 0.7f
        return if (long.size >= 2 && dist(long[0].third, long.last().third) > 34) {
            BarEl(mid(p, w, l + long[0].first, bandMid, long[0].second), mid(p, w, l + long.last().first, bandMid, long.last().second), long[0].second.toFloat() / ew, thick, knobAt, rounded, l, t, l + ew, t + eh)
        } else {
            BarEl(mid(p, w, l, bandMid, ew), null, null, thick, knobAt, rounded, l, t, l + ew, t + eh)
        }
    }

    /** The middle color of a stretch of a row. */
    private fun mid(p: IntArray, w: Int, x: Int, y: Int, len: Int): Int {
        val k = max(1, len - len / 5 * 2)
        val from = x + len / 5
        return rowColor(p, w, y, from, min(w, from + k))
    }

    private fun radiusOf(p: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int, bgAt: (Int, Int) -> Int): Int {
        val limit = min(r - l, b - t) / 2
        fun corner(x0: Int, y0: Int, sx: Int, sy: Int): Int {
            var d = 0
            while (d < limit) {
                val x = x0 + sx * d
                val y = y0 + sy * d
                if (dist(p[y * w + x], bgAt(x, y)) > FG) break
                d++
            }
            return (d / 0.293f).roundToInt()
        }
        val rs = intArrayOf(corner(l, t, 1, 1), corner(r - 1, t, -1, 1), corner(l, b - 1, 1, -1), corner(r - 1, b - 1, -1, -1))
        val m = median(rs, 4)
        return if (m < 3) 0 else min(m, limit)
    }

    /** How much of an area is its most common color (0..1). */
    private fun mainShare(p: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): Float {
        val counts = HashMap<Int, Int>()
        val step = max(1, min(r - l, b - t) / 50)
        var all = 0
        for (y in t until b step step) for (x in l until r step step) {
            val c = p[y * w + x]
            val key = (r(c) shr 4 shl 8) or (g(c) shr 4 shl 4) or (b(c) shr 4)
            counts[key] = (counts[key] ?: 0) + 1
            all++
        }
        return if (all == 0) 0f else (counts.values.maxOrNull() ?: 0).toFloat() / all
    }

    /** The 2–3 colors most of a picture is made of. */
    private fun dominant(p: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int): List<Int> {
        val counts = HashMap<Int, IntArray>()
        val step = max(1, min(r - l, b - t) / 60)
        for (y in t until b step step) for (x in l until r step step) {
            val c = p[y * w + x]
            val key = (r(c) shr 5 shl 6) or (g(c) shr 5 shl 3) or (b(c) shr 5)
            val a = counts.getOrPut(key) { IntArray(4) }
            a[0]++; a[1] += r(c); a[2] += g(c); a[3] += b(c)
        }
        val picked = mutableListOf<Int>()
        for ((_, a) in counts.entries.sortedByDescending { it.value[0] }) {
            val c = rgb(a[1] / a[0], a[2] / a[0], a[3] / a[0])
            if (picked.none { dist(it, c) < 60 }) picked += c
            if (picked.size == 3) break
        }
        return picked
    }

    private fun mainColor(p: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int, mask: BooleanArray, ew: Int): Int {
        val counts = HashMap<Int, IntArray>()
        for (y in t until b) for (x in l until r) {
            if (!mask[(y - t) * ew + (x - l)]) continue
            val c = p[y * w + x]
            val key = (r(c) shr 4 shl 8) or (g(c) shr 4 shl 4) or (b(c) shr 4)
            val a = counts.getOrPut(key) { IntArray(4) }
            a[0]++; a[1] += r(c); a[2] += g(c); a[3] += b(c)
        }
        val a = counts.values.maxByOrNull { it[0] } ?: return 0xFF808080.toInt()
        return rgb(a[1] / a[0], a[2] / a[0], a[3] / a[0])
    }

    private fun countNear(p: IntArray, w: Int, l: Int, t: Int, r: Int, b: Int, mask: BooleanArray, ew: Int, c: Int, within: Int): Int {
        var k = 0
        for (y in t until b) for (x in l until r) if (mask[(y - t) * ew + (x - l)] && dist(p[y * w + x], c) <= within) k++
        return k
    }

    private fun boxColor(group: List<Int>, p: IntArray, w: Int, h: Int, top: Int, cell: Int, cols: Int, inText: BooleanArray, fallback: Int): Int {
        val plain = group.filter { !inText[it] }.ifEmpty { group }
        val rs = IntArray(plain.size)
        val gs = IntArray(plain.size)
        val bs = IntArray(plain.size)
        plain.forEachIndexed { k, i ->
            val x = (i % cols) * cell + cell / 2
            val y = top + (i / cols) * cell + cell / 2
            val c = p[min(h - 1, y) * w + min(w - 1, x)]
            rs[k] = r(c); gs[k] = g(c); bs[k] = b(c)
        }
        return if (plain.isEmpty()) fallback else rgb(median(rs, rs.size), median(gs, gs.size), median(bs, bs.size))
    }

    /** A text line: the color of its letters, its size (em, in picture pixels) and whether it is bold. */
    private fun textOf(l: TextLine, p: IntArray, w: Int, h: Int): TextEl {
        val x0 = l.left.coerceIn(0, w - 1)
        val x1 = l.right.coerceIn(x0 + 1, w)
        val y0 = l.top.coerceIn(0, h - 1)
        val y1 = l.bottom.coerceIn(y0 + 1, h)
        // The background of the line: the middle color of its edge pixels.
        val edge = mutableListOf<Int>()
        for (x in x0 until x1) { edge += p[y0 * w + x]; edge += p[(y1 - 1) * w + x] }
        for (y in y0 until y1) { edge += p[y * w + x0]; edge += p[y * w + x1 - 1] }
        val bg = rgb(median(edge.map { r(it) }.toIntArray(), edge.size), median(edge.map { g(it) }.toIntArray(), edge.size), median(edge.map { b(it) }.toIntArray(), edge.size))
        val ds = IntArray((x1 - x0) * (y1 - y0))
        var k = 0
        for (y in y0 until y1) for (x in x0 until x1) ds[k++] = dist(p[y * w + x], bg)
        val sorted = ds.copyOf(k).also { it.sort() }
        val p95 = sorted[(k * 0.95).toInt().coerceAtMost(k - 1)]
        val cut = max(25, (p95 * 0.6).toInt())
        // The letters' own color: the pixels farthest from the background (edges are blended).
        val inkCut = max(cut, sorted[(k * 0.97).toInt().coerceAtMost(k - 1)] * 85 / 100)
        var sr = 0L; var sg = 0L; var sb = 0L; var ink = 0
        // Stroke width: the length of runs of letter pixels across each row.
        val runsLen = mutableListOf<Int>()
        for (y in y0 until y1) {
            var run = 0
            for (x in x0 until x1) {
                val c = p[y * w + x]
                val d = dist(c, bg)
                if (d >= inkCut) { sr += r(c); sg += g(c); sb += b(c); ink++ }
                if (d >= cut) {
                    run++
                } else {
                    if (run > 0) runsLen += run
                    run = 0
                }
            }
            if (run > 0) runsLen += run
        }
        val color = if (ink == 0) (if (luma(bg) < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()) else rgb((sr / ink).toInt(), (sg / ink).toInt(), (sb / ink).toInt())
        val text = TextQuality.fixOcrDigits(l.text.replace('\n', ' ').trim())
        val hasAscent = text.any { it.isUpperCase() || it.isDigit() || it in "bdfhklt'\"" || (it.isLetter() && !it.isLowerCase()) }
        val hasDescent = text.any { it in "gjpqy,;" }
        val emFactor = (if (hasAscent) 0.72f else 0.52f) + (if (hasDescent) 0.21f else 0f)
        val em = (y1 - y0) / emFactor
        val stroke = if (runsLen.isEmpty()) 0f else runsLen.sorted()[runsLen.size / 2].toFloat()
        val bold = em >= 8 && stroke / em >= 0.115f
        return TextEl(text, em, color, bold, x0, y0, x1, y1)
    }
}
