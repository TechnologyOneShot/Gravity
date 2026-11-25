package com.gravity.ide

import com.gravity.ide.core.LoopGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopGuardTest {
    @Test fun aThreeLineLoopIsCutAfterTheFirstCopy() {
        val intro = "मैं आपके साथ चर्चा कर सकता हूँ।\n"
        val block = "मैं आपकी लिए अपने परीक्षण में सफलता प्रदान कर सकता हूँ।\nमैं आपकी लिए अपने ध्यान को बढ़ावा देता हूँ।\nमैं आपकी लिए अपने स्वास्थ्य को बढ़ावा देता हूँ।\n"
        val text = intro + block + block + block
        assertEquals(intro.length + block.length, LoopGuard.loopStart(text))
    }

    @Test fun normalTextIsNotALoop() {
        assertEquals(-1, LoopGuard.loopStart("Hello! Kaise ho?\nMain theek hoon.\nAap batao, aaj kya karna hai?\n"))
        // A chorus twice is a song, not a loop.
        val chorus = "चाँदनी रात, सितारे जगमगाएँ,\nदिल में है प्यार, खुशी का रंग जगाएँ।\n"
        assertEquals(-1, LoopGuard.loopStart("गाना:\n" + chorus + "हवा में खुशबू\n" + chorus + chorus.substring(0, 10)))
        // Rules and closing braces repeat but carry no words.
        assertEquals(-1, LoopGuard.loopStart("text\n" + "-".repeat(90)))
        assertEquals(-1, LoopGuard.loopStart("fun a() {\n    if (x) {\n        y()\n    }\n}\n}\n}\n}\n"))
    }
}
