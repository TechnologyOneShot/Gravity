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

    @Test fun pictureLinesAreAskedInEnglish() {
        assertEquals(
            "हाथी की तस्वीर बनाओ\n\n(Write every Picture line in English. Reply to the user in Hindi, written in Devanagari.)",
            LanguageHint.withPictureHint("हाथी की तस्वीर बनाओ"),
        )
        assertEquals(
            "draw a red car\n\n(Write every Picture line in English. Reply in English.)",
            LanguageHint.withPictureHint("draw a red car"),
        )
    }

    @Test fun hindiScriptIsSpotted() {
        assertEquals(true, LanguageHint.mostlyNonLatin("एक विशाल एशियाई हाथी घास के मैदान में खड़ा है"))
        assertEquals(false, LanguageHint.mostlyNonLatin("A photorealistic Asian elephant standing in a meadow"))
        assertEquals(false, LanguageHint.mostlyNonLatin("12345 !!"))
    }

    /** A language asked for by name wins over the script the message is written in. */
    @Test fun askedLanguageWins() {
        fun asked(expected: Lang?, vararg texts: String) = texts.forEach { assertEquals(it, expected, LanguageHint.asked(it)) }
        // The user's case: Gemma was told "(Reply in English.)" and wrote English with Hindi in brackets.
        asked(Lang.HINDI_DEVANAGARI, "more and hindi text", "Write it in Hindi", "class 2 gk hindi me do", "translate this English text into Hindi", "इसे हिंदी में लिखो", "mere liye ek Hindi mein gana Gao")
        asked(Lang.ENGLISH, "Now a different question, in English please: why is the sky blue?", "isko english me batao", "इसे अंग्रेजी में बताओ")
        asked(Lang.HINGLISH, "hinglish me likho", "reply in Hinglish")
        asked(null, "What is the Hindi word for apple?", "Name three planets.", "hindi and english both", "kya haal hai")
        assertEquals(
            "more and hindi text\n\n(This asks for more of your previous answer: continue it with new items, do not repeat them.) " +
                "(The user asks for Hindi: reply only in Hindi, written in Devanagari, with no English translation.)",
            LanguageHint.withHint("more and hindi text"),
        )
        listOf("more", "aur do", "continue", "10 more please", "aur batao", "more questions in hindi").forEach { assertEquals(it, true, LanguageHint.isFollowUp(it)) }
        listOf("What is photosynthesis? Explain in 2 lines.", "mere liye ek gana gao", "Name three planets.", "more people live in cities than villages, why is that so?")
            .forEach { assertEquals(it, false, LanguageHint.isFollowUp(it)) }
    }
}

