package com.gravity.ide

import com.gravity.ide.core.DesignReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The design reader on drawn screens: a dark music player (a photo with words on it, a two-tone
 * progress bar, ⏪ ⏸ ⏩ controls, a round ⋯ button, a ☰ icon, OCR junk read from icons) and a light
 * sign-in screen (outlined fields, a pill button, faint round buttons), plus a browser bar on top.
 * The OCR lines are given as ML Kit would give them.
 */
class DesignReaderTest {
    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }
        fun rect(l: Int, t: Int, r: Int, b: Int, c: Int) { for (y in t until b) for (x in l until r) set(x, y, c) }
        fun gradient(top: Int, bottom: Int) {
            for (y in 0 until h) {
                val f = y.toFloat() / (h - 1)
                val c = mix(top, bottom, f)
                for (x in 0 until w) px[y * w + x] = c
            }
        }
        fun circle(cx: Int, cy: Int, rad: Int, c: Int) {
            for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) set(x, y, c)
        }
        fun roundRect(l: Int, t: Int, r: Int, b: Int, rad: Int, c: Int) {
            for (y in t until b) for (x in l until r) {
                val dx = max(0, max(l + rad - x, x - (r - 1 - rad)))
                val dy = max(0, max(t + rad - y, y - (b - 1 - rad)))
                if (dx * dx + dy * dy <= rad * rad) set(x, y, c)
            }
        }
        fun outline(l: Int, t: Int, r: Int, b: Int, rad: Int, thick: Int, c: Int, inside: Int) {
            roundRect(l, t, r, b, rad, c)
            roundRect(l + thick, t + thick, r - thick, b - thick, max(0, rad - thick), inside)
        }
        /** A triangle in the box, pointing right (or left). */
        fun triangle(l: Int, t: Int, r: Int, b: Int, right: Boolean, c: Int) {
            val mid = (t + b) / 2.0
            for (x in l until r) {
                val f = if (right) (r - x).toDouble() / (r - l) else (x - l + 1).toDouble() / (r - l)
                val half = (b - t) / 2.0 * f
                for (y in (mid - half).toInt() until (mid + half).toInt()) set(x, y, c)
            }
        }
        /** A colorful, busy area like a photo. */
        fun photo(l: Int, t: Int, r: Int, b: Int) {
            var seed = 12345
            for (y in t until b) for (x in l until r) {
                seed = seed * 1103515245 + 12345
                val n = (seed ushr 16) and 63
                val base = if (y < (t + b) / 2) rgb(70 + (x - l) / 6, 120, 60) else rgb(160, 130 + (y - t) / 8, 100)
                set(x, y, rgb(((base shr 16) and 255) + n - 32, ((base shr 8) and 255) + n - 32, (base and 255) + n / 2))
            }
        }
        /** Fake letters: upright strokes, as many as fit. */
        fun letters(l: Int, t: Int, r: Int, b: Int, stroke: Int, c: Int) {
            var x = l
            while (x + stroke <= r) {
                rect(x, t + (if ((x / stroke) % 3 == 0) 0 else (b - t) / 4), x + stroke, b, c)
                rect(x, t + (b - t) / 2, min(r, x + stroke * 2), t + (b - t) / 2 + stroke, c)
                x += stroke * 3
            }
        }
        fun pixels() = DesignReader.Pixels(w, h, px)
    }

    companion object {
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 255
            return rgb(
                (ch(a, 16) + (ch(b, 16) - ch(a, 16)) * f).toInt(),
                (ch(a, 8) + (ch(b, 8) - ch(a, 8)) * f).toInt(),
                (ch(a, 0) + (ch(b, 0) - ch(a, 0)) * f).toInt(),
            )
        }
        val WHITE = rgb(255, 255, 255)
        fun line(text: String, l: Int, t: Int, r: Int, b: Int) = DesignReader.TextLine(text, l, t, r, b)
    }

    private fun player(): Pair<Canvas, List<DesignReader.TextLine>> {
        val c = Canvas(720, 1280, 0)
        c.gradient(rgb(30, 58, 18), rgb(70, 40, 14))
        c.letters(30, 12, 100, 40, 4, WHITE) // the phone's clock in the status bar
        c.photo(130, 150, 590, 610)
        c.letters(300, 180, 420, 220, 5, WHITE) // words printed on the photo
        c.letters(60, 660, 300, 700, 7, WHITE) // title, bold
        c.letters(60, 715, 420, 745, 3, rgb(170, 170, 150)) // artist
        c.circle(640, 690, 24, rgb(90, 70, 45))
        for (dx in listOf(-10, 0, 10)) c.circle(640 + dx, 690, 3, WHITE)
        c.rect(60, 790, 210, 798, rgb(200, 200, 192))
        c.rect(210, 790, 660, 798, rgb(96, 96, 80))
        c.letters(60, 815, 110, 835, 3, rgb(160, 160, 150))
        c.letters(610, 815, 660, 835, 3, rgb(160, 160, 150))
        c.triangle(150, 905, 190, 955, false, WHITE); c.triangle(190, 905, 230, 955, false, WHITE)
        c.rect(334, 900, 350, 960, WHITE); c.rect(372, 900, 388, 960, WHITE)
        c.triangle(490, 905, 530, 955, true, WHITE); c.triangle(530, 905, 570, 955, true, WHITE)
        for (k in 0..2) c.rect(520, 1150 + k * 14, 566, 1156 + k * 14, rgb(200, 200, 200))
        val lines = listOf(
            line("9:41", 30, 12, 100, 40),
            line("DeereR weLL", 300, 180, 420, 220),
            line("Song Title", 60, 660, 300, 700),
            line("Artist Name", 60, 715, 420, 745),
            line("0:46", 60, 815, 110, 835),
            line("-3:06", 610, 815, 660, 835),
            line("<<", 150, 900, 230, 960),
            line("il", 334, 895, 388, 965),
        )
        return c to lines
    }

    @Test fun aMusicPlayerIsMeasuredPartByPart() {
        val (c, lines) = player()
        val out = DesignReader.describe(c.pixels(), lines)
        println(out)
        assertTrue(out, out.startsWith("Screen: dark"))
        assertTrue("The gradient background: $out", out.contains("Background (CSS): linear-gradient(180deg"))
        assertFalse("The phone's clock is not part of the app: $out", out.contains("9:41"))
        assertTrue(out, out.contains("phone bar above the app was left out"))
        val rows = out.lines()
        fun row(part: String) = rows.indexOfFirst { it.contains(part) }.also { assertTrue("No \"$part\" in: $out", it >= 0) }
        val picture = row("picture ")
        assertTrue(rows[picture], rows[picture].contains("the words printed on it belong to the photo") && rows[picture].contains("centered"))
        assertTrue("85 px photo width ≈ 64%: ${rows[picture]}", Regex("\\((6[2-6])% wide\\)").containsMatchIn(rows[picture]))
        assertFalse("Words on the photo are not app text: $out", out.contains("DeereR"))
        val title = row("\"Song Title\"")
        assertTrue("The title is bold: ${rows[title]}", Regex("\"Song Title\" \\d+ px bold #ffffff").containsMatchIn(rows[title]))
        assertTrue("The round ⋯ button sits beside the title: ${rows[title]}", rows[title].contains("round button") && rows[title].contains("⋯"))
        val artist = row("\"Artist Name\"")
        assertFalse("The artist is not bold: ${rows[artist]}", rows[artist].contains("\"Artist Name\" \\d+ px bold".toRegex()))
        val bar = row("px thick")
        assertTrue("A two-tone progress bar, 25% filled: ${rows[bar]}", Regex("#c8c8c0 from the left to 2[4-6]%, then #606050").containsMatchIn(rows[bar]))
        val times = row("\"0:46\"")
        assertTrue("Both times on one row: ${rows[times]}", rows[times].contains("\"-3:06\"") && rows[times].contains("across the width"))
        val controls = row("⏸ (pause)")
        assertTrue("⏪ ⏸ ⏩ on one row, in order: ${rows[controls]}", rows[controls].indexOf("⏪") in 0 until rows[controls].indexOf("⏸") && rows[controls].indexOf("⏸") < rows[controls].indexOf("⏩"))
        assertFalse("OCR read from icons is not text: $out", out.contains("\"il\"") || out.contains("\"<<\""))
        row("☰")
        assertTrue("Top to bottom: $out", picture < title && title < artist && artist < bar && bar < times && times < controls)
        assertTrue(out.length <= DesignReader.MAX_CHARS)
    }

    @Test fun theStartingPageHasTheDesignsLook() {
        val (c, lines) = player()
        val page = DesignReader.read(c.pixels(), lines).layout
        println(page)
        assertTrue(page, page.startsWith("<!DOCTYPE html>") && page.endsWith("</html>") && page.contains("name=\"viewport\""))
        assertTrue("The gradient background: $page", page.contains("background:linear-gradient(180deg"))
        assertTrue("The photo as a box of its colors: $page", Regex("class=\"photo\" role=\"img\" style=\"width:6[2-6]%;aspect-ratio:\\d+/\\d+").containsMatchIn(page))
        assertTrue("Bold title: $page", Regex("font-weight:700;color:#ffffff[^>]*>Song Title<").containsMatchIn(page))
        assertTrue("A two-tone bar with its fill: $page", Regex("class=\"fill\" style=\"width:2[4-6]%;height:100%;background:#c8c8c0").containsMatchIn(page))
        assertTrue("Icons as SVG buttons named by their shape: $page", page.contains("aria-label=\"pause\"") && page.contains("aria-label=\"rewind / previous\"") && page.contains("aria-label=\"more\""))
        assertTrue("Controls placed across the width: $page", Regex("aria-label=\"pause\" style=\"color:#ffffff;position:absolute;top:50%;left:50%").containsMatchIn(page))
        assertFalse(page.contains("DeereR") || page.contains("9:41") || page.contains(">il<"))
        // Every tag that is opened is closed.
        for (tag in listOf("div", "span", "button", "svg")) assertTrue(tag, Regex("<$tag[ >]").findAll(page).count() == Regex("</$tag>").findAll(page).count())
    }

    @Test fun textOnTheStartingPageIsEscaped() {
        val c = Canvas(720, 1280, WHITE)
        c.letters(54, 200, 500, 250, 6, rgb(0, 0, 0))
        val page = DesignReader.read(c.pixels(), listOf(line("Tom & <Jerry> \"show\"", 54, 200, 500, 250))).layout
        assertTrue(page, page.contains(">Tom &amp; &lt;Jerry&gt; &quot;show&quot;<"))
    }

    @Test fun aSignInScreenHasFieldsAndAButton() {
        val c = Canvas(720, 1280, WHITE)
        c.letters(30, 12, 100, 40, 4, rgb(0, 0, 0))
        c.letters(54, 200, 500, 250, 8, rgb(17, 24, 39))
        c.outline(54, 400, 666, 500, 16, 3, rgb(209, 213, 219), WHITE)
        c.letters(90, 435, 170, 465, 3, rgb(156, 163, 175))
        c.outline(54, 530, 666, 630, 16, 3, rgb(209, 213, 219), WHITE)
        c.letters(90, 565, 230, 595, 3, rgb(156, 163, 175))
        c.roundRect(54, 700, 666, 800, 50, rgb(79, 70, 229))
        c.letters(300, 735, 420, 765, 5, WHITE)
        for (cx in listOf(250, 360, 470)) c.circle(cx, 950, 40, rgb(243, 244, 246))
        val lines = listOf(
            line("9:41", 30, 12, 100, 40), line("Welcome back", 54, 200, 500, 250),
            line("Email", 90, 435, 170, 465), line("Password", 90, 565, 230, 595), line("Sign in", 300, 735, 420, 765),
        )
        val out = DesignReader.describe(c.pixels(), lines)
        println(out)
        assertTrue(out, out.startsWith("Screen: light") && out.contains("Background (CSS): #ffffff\n"))
        val rows = out.lines()
        assertTrue("An outlined Email field: $out", rows.any { it.contains("outlined box") && it.contains("\"Email\"") })
        assertTrue("An outlined Password field: $out", rows.any { it.contains("outlined box") && it.contains("\"Password\"") })
        assertTrue("A pill button with its label: $out", rows.any { it.contains("box #4f46e5") && it.contains("pill-shaped") && it.contains("\"Sign in\"") })
        assertTrue("Three faint round buttons in a row: $out", rows.any { Regex("3 side by side.*round button.*round button.*round button").containsMatchIn(it) })
        val page = DesignReader.read(c.pixels(), lines).layout
        assertTrue("Fields become inputs with their placeholder: $page", page.contains("<input placeholder=\"Email\"") && page.contains("<input placeholder=\"Password\""))
        assertTrue("The pill button keeps its label: $page", Regex("<button style=\"width:85%;min-height:\\d+px;border-radius:999px;background:#4f46e5[^>]*><span[^>]*>Sign in</span></button>").containsMatchIn(page))
    }

    @Test fun aBrowserBarAboveTheAppIsLeftOut() {
        val c = Canvas(720, 900, rgb(20, 20, 24))
        c.rect(0, 0, 720, 70, rgb(253, 253, 253))
        c.letters(260, 25, 460, 45, 3, rgb(60, 60, 60))
        c.letters(40, 200, 300, 236, 6, WHITE)
        val out = DesignReader.describe(c.pixels(), listOf(line("a music.example.com 4)", 260, 25, 460, 45), line("Now playing", 40, 200, 300, 236)))
        assertFalse(out, out.contains("example.com"))
        assertTrue(out, out.contains("browser or phone bar above the app was left out") && out.contains("\"Now playing\"") && out.startsWith("Screen: dark"))
        assertFalse("The bar's edge is not a part: $out", out.contains("bar 1 px") || out.contains("divider"))
    }

    @Test fun iconReadingsAreNotText() {
        val typical = 20
        for (junk in listOf("4)", "<<", "D)", "+", "|")) assertTrue(junk, DesignReader.isJunk(line(junk, 0, 0, 20, 20), typical))
        assertTrue("A tall short word over an icon", DesignReader.isJunk(line("il", 0, 0, 40, 50), typical))
        for (ok in listOf("OK", "Go", "0:46", "-3:06", "12", "Sign in")) assertFalse(ok, DesignReader.isJunk(line(ok, 0, 0, 40, 20), typical))
        assertTrue("Unsure OCR", DesignReader.isJunk(DesignReader.TextLine("Hello", 0, 0, 40, 20, confidence = 0.2f), typical))
    }

    @Test fun nothingToMeasureGivesNothing() {
        assertTrue(DesignReader.describe(DesignReader.Pixels(10, 10, IntArray(100)), emptyList()).isEmpty())
        val plain = Canvas(400, 700, WHITE)
        val out = DesignReader.describe(plain.pixels(), emptyList())
        assertTrue(out, out.contains("Background (CSS): #ffffff") && out.contains("no separate parts"))
        assertTrue(abs(out.length) < 400)
    }
}
