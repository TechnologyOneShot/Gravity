package com.gravity.ide

import com.gravity.ide.core.HinglishReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HinglishReaderTest {
    private val words by lazy {
        val f = listOf(File("src/main/assets/hinglish-words.bin"), File("app/src/main/assets/hinglish-words.bin")).first { it.isFile }
        f.inputStream().use(HinglishReader::parse)
    }

    @Test fun hinglishIsWrittenInDevanagariForTheVoice() {
        val r = HinglishReader.read("Arre, ye toh bahut achha hai! Main aapke liye ek gaana bana sakti hoon.", words)
        assertEquals("अरे, ये तो बहुत अच्छा है! मैं आपके लिए एक गाना बना सकती हूँ.", r.text)
        assertTrue(r.wasHinglish)
    }

    @Test fun englishWordsInHinglishStayEnglish() {
        val r = HinglishReader.read("Maine aapke liye ek music player app bana diya hai.", words)
        assertEquals("मैंने आपके लिए एक music player app बना दिया है.", r.text)
    }

    @Test fun englishSentencesAreLeftAlone() {
        val text = "The weather is really nice today, shall we go out? I can help you with that."
        val r = HinglishReader.read(text, words)
        assertEquals(text, r.text)
        assertFalse(r.wasHinglish)
    }

    @Test fun devanagariAndMixedSentences() {
        val r = HinglishReader.read("नमस्ते! Aap kaise ho? I am fine.", words)
        assertEquals("नमस्ते! आप कैसे हो? I am fine.", r.text)
    }

    @Test fun commonSpellingsAllWork() {
        for ((typed, want) in listOf("nahi" to "नहीं", "nahin" to "नहीं", "bohot" to "बहुत", "accha" to "अच्छा", "kyun" to "क्यों", "zindagi" to "ज़िंदगी", "samjhna" to "समझना")) {
            assertEquals(typed, want, HinglishReader.read("Mujhe $typed pata hai", words).text.split(' ')[1])
        }
    }
}
