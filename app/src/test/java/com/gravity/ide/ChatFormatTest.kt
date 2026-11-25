package com.gravity.ide

import com.gravity.ide.core.ChatFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFormatTest {
    private val lyrics = "Aankhen mein aapki jaankari,\nMain kya kar sakta hoon aapke liye.\nKuchh baat main karte hoon aapke saath,\nAur aapki zindagi mein kuchh aur bhi."

    @Test fun lyricsInACodeFenceAreProse() {
        assertTrue(ChatFormat.isProse(null, lyrics))
        assertTrue(ChatFormat.isProse("text", lyrics))
        assertTrue(ChatFormat.isProse(null, "दिल की बातें तुमसे कहनी हैं,\nचाँदनी रातें साथ बितानी हैं।"))
    }

    @Test fun codeStaysCode() {
        assertFalse(ChatFormat.isProse("kotlin", lyrics))
        assertFalse(ChatFormat.isProse(null, "fun main() {\n    println(\"hi\")\n}"))
        assertFalse(ChatFormat.isProse(null, "const x = a.map(v => v * 2);"))
        assertFalse(ChatFormat.isProse(null, "SELECT name FROM users WHERE id = 3"))
    }
}
