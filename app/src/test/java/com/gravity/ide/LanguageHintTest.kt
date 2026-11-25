package com.gravity.ide

import com.gravity.ide.core.LanguageHint
import com.gravity.ide.core.LanguageHint.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageHintTest {
    private fun check(expected: Lang, vararg texts: String) = texts.forEach { assertEquals(it, expected, LanguageHint.detect(it)) }

    @Test fun hinglishMessagesFromRealUsers() = check(
        Lang.HINGLISH,
        "Bahut late reply kar raha hai isko fast se bhi fast super fast banao",
        "App apne aap hi close ho jata hai",
        "Buld apk ka link do fir apna karte rahna",
        "Tum Aisa pura Baat batao ki tum mere liye kya-kya kar sakte ho aur kya-kya Nahin kar sakte ho sab ek-ek line bye line Karke batao",
        "mujhe kal subah 6 baje uthna hai, koi 3 tips do",
        "Mera phone bahut slow chal raha hai, kya karu?",
        "mere liye ek Hindi mein gana Gao",
        "music player banao",
        "kya haal hai",
        "ok bhai",
    )

    @Test fun englishIsNotTakenForHinglish() = check(
        Lang.ENGLISH,
        "What is photosynthesis? Explain in 2 lines.",
        "Name three planets.",
        "What can you do for me, and what can't you do? Tell me line by line.",
        "Now a different question, in English please: why is the sky blue?",
        "I want to go to the market to buy some food",
        "Open a new tab and jab the button, then sit on the mat by the fir tree",
        "Write a poem about the sun and the moon",
        "Make a gallery app with a dark theme",
    )

    @Test fun devanagari() = check(
        Lang.HINDI_DEVANAGARI,
        "भारत की राजधानी क्या है?",
        "तुम मेरे लिए क्या-क्या कर सकते हो और क्या-क्या नहीं कर सकते? एक-एक लाइन में बताओ।",
        "मुझे python में एक calculator बनाना है",
    )

    @Test fun tooShortToTell() = check(Lang.UNKNOWN, "hi", "ok", "hello", "👍", "?")

    @Test fun hintIsOnlyAddedWhenSure() {
        assertEquals("hello", LanguageHint.withHint("hello"))
        assertEquals("Name three planets.\n\n(Reply in English.)", LanguageHint.withHint("Name three planets."))
    }
}
