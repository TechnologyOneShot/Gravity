package com.gravity.ide

import com.gravity.ide.core.BuildNarration
import com.gravity.ide.core.BuildNarration.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildNarrationTest {
    @Test fun aRealBuildLogReadsLikeAnAssistantExplainingItsWork() {
        // The titles exactly as AgentEngine logs them for a single-file app with a fix.
        val log = listOf(
            Entry("AGENT", "Task created: Music player", "", "INFO"),
            Entry("AGENT", "Task started", "", "INFO"),
            Entry("AGENT", "Writing the app · gemini-3-flash", "", "INFO"),
            Entry("AGENT", "Single-file app with gemini-3-flash", "", "INFO"),
            Entry("AGENT", "Calling gemini-3-flash", "Developer · 2 messages · 0 tools", "INFO"),
            Entry("TOOL", "Wrote index.html (12,345 characters)", "write_file (single-file app)", "INFO"),
            Entry("AGENT", "Testing the app (opening it and tapping every button)", "", "INFO"),
            Entry("TEST", "App test: 1 error(s), 6 button(s) tapped", "Uncaught ReferenceError: play is not defined (line 40)", "WARN"),
            Entry("AGENT", "The test found 1 error(s); fixing them", "", "INFO"),
            Entry("TOOL", "Wrote index.html (12,400 characters)", "write_file (fix after test)", "INFO"),
            Entry("AGENT", "Testing the fixed app", "", "INFO"),
            Entry("TEST", "App test after the fix: 0 error(s)", "", "INFO"),
            Entry("NOTIFY", "Task completed", "Built the app…", "INFO"),
        )
        assertEquals(
            listOf(
                "I'm writing the app with gemini-3-flash…",
                "Wrote index.html (12,345 characters).",
                "Now testing it: opening the app and tapping every button…",
                "Tested it: tapped 6 buttons and found 1 error: Uncaught ReferenceError: play is not defined (line 40)",
                "Found 1 error. Fixing it…",
                "Saved the fixed version (12,400 characters).",
                "Testing the fixed version…",
                "Tested the fixed version: no errors now.",
            ),
            BuildNarration.lines(log),
        )
    }

    @Test fun phoneModelAndCleanTest() {
        assertEquals("I'm writing the app with the phone model…", BuildNarration.line("AGENT", "Writing the app · phone model, up to 6000 tokens", "", "INFO"))
        assertEquals("Tested it on this phone: opened the app and tapped 1 button — no errors.", BuildNarration.line("TEST", "App test: 0 error(s), 1 button(s) tapped", "", "INFO"))
        assertEquals(null, BuildNarration.line("AGENT", "Calling qwen", "", "INFO"))
    }

    @Test fun theAgentsFileWorkIsSaidAsWhatItDoes() {
        assertEquals("Created index.html (1,234 characters).", BuildNarration.line("TOOL", "Created index.html", "1234 chars", "INFO"))
        assertEquals("Modified app.js (rewrote it, 980 characters).", BuildNarration.line("TOOL", "Updated app.js", "980 chars", "INFO"))
        assertEquals("Modified style.css: replaced 1 occurrence(s).", BuildNarration.line("TOOL", "Edited style.css", "Replaced 1 occurrence(s)", "INFO"))
        assertEquals("Reading index.html…", BuildNarration.line("TOOL", "Reading index.html", "", "INFO"))
        assertEquals("Running: ls -la", BuildNarration.line("TOOL", "Running: ls -la", "", "INFO"))
        assertEquals("Failed (exit 2): make", BuildNarration.line("TOOL", "Exit 2: make", "", "WARN")?.removeSuffix("."))
        assertEquals("Created the folder assets.", BuildNarration.line("TOOL", "Created folder assets", "", "INFO"))
        // Not tool lines.
        assertEquals("I'm studying your design picture: layout, positions, colors and text…", BuildNarration.line("AGENT", "Reading your design picture(s): 1", "", "INFO"))
    }
}
