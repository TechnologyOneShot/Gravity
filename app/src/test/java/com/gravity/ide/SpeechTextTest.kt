package com.gravity.ide

import com.gravity.ide.core.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun markdownIsNotReadAloud() {
        assertEquals("Title\nBold and italic text.\nitem one\nitem two\nfirst", SpeechText.clean("## Title\n**Bold** and *italic* text.\n- item one\n- item two\n\n1. first"))
        assertEquals("Run npm start, see docs or\n(code not read aloud)\nDone", SpeechText.clean("Run `npm start`, see [docs](https://x.y/z) or https://a.b/c.\n```js\nconsole.log(1)\n```\nDone"))
        assertEquals("नमस्ते! आप कैसे हैं?", SpeechText.clean("नमस्ते! **आप** कैसे हैं?"))
        assertEquals("Hello", SpeechText.clean("Hello\n\n_(generation stopped)_"))
        assertEquals("use my_var here", SpeechText.clean("use my_var here"))
    }

    @Test fun longRepliesAreSplitForTextToSpeech() {
        val long = (1..200).joinToString(" ") { "Sentence number $it is here." }
        val parts = SpeechText.chunks(long, 500)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 500 })
        assertEquals(long, parts.joinToString(" "))
        assertEquals(listOf("hi"), SpeechText.chunks("hi", 10))
    }

    @Test fun liveModeSpeaksFinishedSentencesFirst() {
        assertEquals(0, SpeechText.speakableEnd("Namaste, main", 0))
        assertEquals(13, SpeechText.speakableEnd("Namaste dost! Aaj ka din", 0))
        assertEquals(22, SpeechText.speakableEnd("आज मौसम बहुत अच्छा है। चलो", 0))
        assertEquals(0, SpeechText.speakableEnd("The price is 3.", 0))
        assertEquals(0, SpeechText.speakableEnd("The price is 3.5 lakh rupees for this one", 0))
        assertEquals(44, SpeechText.speakableEnd("Hello there friend. How are you doing today? Fine", 19))
        assertTrue(SpeechText.speakableEnd("word ".repeat(40), 0) > 0)
    }
}
