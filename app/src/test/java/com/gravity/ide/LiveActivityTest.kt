package com.gravity.ide

import com.gravity.ide.core.LiveActivity
import com.gravity.ide.core.LiveActivity.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveActivityTest {
    @Test fun beforeAnyCodeItIsPlanning() {
        assertEquals(Verb.PLANNING, LiveActivity.of("").verb)
        val a = LiveActivity.of("Sure! I'll build a music player with a playlist and shuffle.")
        assertEquals(Verb.PLANNING, a.verb)
        assertEquals("Planning…", a.headline())
    }

    @Test fun aPageBeingWrittenSaysWhichPart() {
        val head = "Here is your app:\n```html\n<!DOCTYPE html>\n<html>\n<head>\n<title>Player</title>\n"
        val layout = LiveActivity.of(head)
        assertEquals(Verb.CREATING, layout.verb)
        assertEquals("index.html", layout.file)
        assertEquals("writing the layout (HTML)", layout.part)
        val css = LiveActivity.of(head + "<style>\nbody { margin: 0; }\n.btn { color: red; }\n")
        assertEquals("writing the styles (CSS)", css.part)
        assertEquals(".btn { color: red; }", css.tail.last())
        val js = LiveActivity.of(head + "<style>body{}</style>\n</head>\n<body><button id=p>Play</button>\n<script>\nconst p = document.getElementById('p');\n")
        assertEquals("writing the logic (JavaScript)", js.part)
        assertTrue(js.headline(), js.headline().startsWith("Creating index.html · ") && js.headline().endsWith("writing the logic (JavaScript)"))
        // Finished block.
        assertEquals("done", LiveActivity.of(head + "</html>\n```\nEnjoy!").part)
    }

    @Test fun namedFilesAndChanges() {
        val a = LiveActivity.of("File: style.css\n```css\nbody { margin: 0 }\n")
        assertEquals("style.css", a.file)
        val change = LiveActivity.of("```html\n<!DOCTYPE html>\n<html><body>\n", change = true)
        assertEquals(Verb.MODIFYING, change.verb)
        assertTrue(change.headline().startsWith("Modifying index.html"))
    }

    @Test fun editsAreCountedAndTheirStepShown() {
        val one = "<<<<<<< SEARCH\n<h1>Old</h1>\n=======\n<h1>New</h1>\n>>>>>>> REPLACE\n"
        val finding = LiveActivity.of(one + "<<<<<<< SEARCH\n<button>")
        assertEquals(Verb.MODIFYING, finding.verb)
        assertEquals(1, finding.editsDone)
        assertEquals("finding the lines to change", finding.part)
        assertEquals("Modifying index.html · change 2 · finding the lines to change", finding.headline())
        val writing = LiveActivity.of(one + "<<<<<<< SEARCH\n<button>\n=======\n<button class=big>")
        assertEquals("writing the new lines", writing.part)
        assertEquals("<button class=big>", writing.tail.last())
    }
}
