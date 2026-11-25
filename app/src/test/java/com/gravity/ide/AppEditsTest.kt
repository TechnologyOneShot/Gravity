package com.gravity.ide

import com.gravity.ide.agent.AppEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone-model app changes as SEARCH/REPLACE edits (AppEdits). */
class AppEditsTest {
    private val html = """<!DOCTYPE html>
<html>
<head>
  <style>
    body { margin: 0; }
  </style>
</head>
<body>
  <button id="play">Play</button>
  <script>
    function play() { audio.play(); }
  </script>
</body>
</html>"""

    @Test fun parsesAndAppliesEditsAfterTheForcedPrefix() {
        val reply = AppEdits.PREFIX + "  </style>\n=======\n    .eq { display: flex; }\n  </style>\n>>>>>>> REPLACE\n\n" +
            "<<<<<<< SEARCH\n<button id=\"play\">Play</button>\n=======\n<button id=\"play\">Play</button>\n<div class=\"eq\"></div>\n>>>>>>> REPLACE\n" +
            "<<<<<<< SEARCH\n    function missing() {}\n=======\n    function nope() {}\n>>>>>>> REPLACE\n" +
            "<<<<<<< SEARCH\n  </script>\n=======\n    function setBass(v) { return v; }\n  </script>\n>>>>>>> REPLACE"
        val edits = AppEdits.parse(reply)
        assertEquals(4, edits.size)
        val r = AppEdits.apply(html, edits)
        assertEquals(3, r.applied)
        assertEquals(listOf("function missing() {}"), r.failed)
        assertTrue(r.html.contains(".eq { display: flex; }\n  </style>"))
        assertTrue(r.html.contains("<div class=\"eq\"></div>"))
        assertTrue(r.html.contains("function setBass(v)"))
        assertEquals(1, Regex("</html>").findAll(r.html).count())
    }

    @Test fun matchesLinesWithDifferentIndentation() {
        val r = AppEdits.apply(html, listOf(AppEdits.Edit("<script>\nfunction play() { audio.play(); }", "<script>\nfunction play() { audio.play(); log(); }")))
        assertEquals(1, r.applied)
        assertTrue(r.html.contains("log(); }"))
        assertTrue(r.html.contains("  </script>"))
    }

    @Test fun windowsLineEndingsAndEmptySearch() {
        val edits = AppEdits.parse("<<<<<<< SEARCH\r\n  </style>\r\n=======\r\n  .x{}\r\n  </style>\r\n>>>>>>> REPLACE\r\n<<<<<<< SEARCH\n=======\nnew\n>>>>>>> REPLACE")
        assertEquals(1, edits.size)
        assertEquals("  .x{}\n  </style>", edits[0].replace)
        assertTrue(AppEdits.parse("```html\n<html></html>\n```").isEmpty())
    }
}
