package com.gravity.ide.core

/**
 * Which language the user wrote their latest message in, found by the app (not guessed by the
 * model), and a one-line reply instruction for it. Small phone models follow "reply in the
 * user's language" badly: tests showed English questions answered in Hindi and Hinglish
 * questions answered in English or in Devanagari. A plain instruction on the message itself is
 * followed far better. It goes only on the newest message, so the model's saved reading of the
 * chat before it still matches.
 */
object LanguageHint {

    enum class Lang { HINDI_DEVANAGARI, HINGLISH, ENGLISH, UNKNOWN }

    /** Common words of Hindi written in English letters (not English words). */
    private val hinglishWords = setOf(
        "hai", "hain", "ho", "hoga", "hogi", "hota", "hoti", "tha", "thi", "the", "kya", "kyu", "kyun", "kyon", "kaise", "kaisa",
        "kaun", "kab", "kahan", "kitna", "kitne", "mujhe", "mujhko", "mera", "meri", "mere", "tum", "tumhe", "tumhara", "tumhari",
        "aap", "aapka", "aapki", "apna", "apni", "apne", "hum", "hame", "hamara", "nahi", "nahin", "nhi", "mat", "kar", "karo",
        "karna", "karke", "karu", "karun", "kardo", "kariye", "karta", "karti", "karte", "raha", "rahi", "rahe", "rha", "rhi",
        "sakte", "sakta", "sakti", "batao", "bataiye", "bata", "banao", "bana", "bolo", "bol", "dikhao", "do", "diya", "de", "dena",
        "lo", "liya", "le", "lena", "ke", "ki", "ka", "ko", "se", "mein", "me", "par", "pe", "aur", "ya", "bhi", "toh", "to",
        "yeh", "ye", "woh", "wo", "isko", "usko", "iska", "uska", "isme", "usme", "kuch", "koi", "sab", "bahut", "bohot", "thoda",
        "accha", "acha", "achha", "theek", "thik", "bhai", "yaar", "chahiye", "wala", "wali", "wale", "abhi", "phir", "fir",
        "jab", "tab", "agar", "lekin", "matlab", "samjhao", "likho", "likh", "padho", "suno", "chalo", "gana", "kahani",
    )

    /** Words that are Hinglish-looking but also plain English, so they never count alone. */
    private val alsoEnglish = setOf(
        "do", "the", "to", "me", "par", "pe", "de", "le", "ho", "bata", "bol", "tab", "jab", "mat", "hum", "lo", "ya", "fir",
        "agar", "koi", "ye", "sab", "wo", "bhi", "sun", "chalo", "likh",
    )

    fun detect(text: String): Lang {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 2) return Lang.UNKNOWN
        val deva = letters.count { it in 'ऀ'..'ॿ' }
        if (deva * 10 >= letters.length * 3) return Lang.HINDI_DEVANAGARI
        val words = Regex("[A-Za-z]+").findAll(text.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return Lang.UNKNOWN
        val strong = words.count { it in hinglishWords && it !in alsoEnglish }
        val weak = words.count { it in alsoEnglish }
        val score = strong + weak / 2
        return when {
            strong >= 1 && (score >= 2 || words.size <= 3) -> Lang.HINGLISH
            words.size >= 2 -> Lang.ENGLISH
            else -> Lang.UNKNOWN
        }
    }

    // A language asked for by name: "in Hindi", "hindi me", "hindi text", "into English", "हिंदी में".
    // "to/into/in X" and "X me/mein/में" (the reply's language) count more than "X text" (could be
    // what is being described), so "translate this English text into Hindi" asks for Hindi.
    private val askForms = listOf(
        Lang.HINDI_DEVANAGARI to listOf("\\b(?:in|into|to)\\s+hindi\\b", "\\bhindi\\s+(?:me|mein|mai|mei|main|mai)\\b", "हि[ंन्]?दी\\s*में", "हिन्दी\\s*में"),
        Lang.ENGLISH to listOf("\\b(?:in|into|to)\\s+english\\b", "\\benglish\\s+(?:me|mein|mai|mei)\\b", "अंग्रे[ज़ज]ी\\s*में", "\\bangrezi\\s+(?:me|mein)\\b"),
        Lang.HINGLISH to listOf("\\b(?:in|into|to)\\s+hinglish\\b", "\\bhinglish\\s+(?:me|mein|mai|mei)\\b"),
    ).map { (lang, forms) -> lang to forms.map { Regex(it) } }
    private val nameForms = listOf(
        Lang.HINDI_DEVANAGARI to Regex("\\bhindi\\s+(?:text|language|version|lang|script|font)\\b|\\b(?:and|aur)\\s+hindi\\b"),
        Lang.ENGLISH to Regex("\\benglish\\s+(?:version|language)\\b"),
        Lang.HINGLISH to Regex("\\bhinglish\\b"),
    )

    /**
     * The reply language the user asks for by name, or null. Without this, "more and hindi text"
     * (English words) got "(Reply in English.)", and Gemma answered in English with Hindi only
     * in brackets.
     */
    fun asked(text: String): Lang? {
        val t = text.lowercase()
        val strong = askForms.filter { (_, forms) -> forms.any { it.containsMatchIn(t) } }.map { it.first }.distinct()
        if (strong.size == 1) return strong.first()
        if (strong.size > 1) return null
        val named = nameForms.filter { it.second.containsMatchIn(t) }.map { it.first }.distinct()
        return named.singleOrNull()
    }

    /** The instruction to put after the user's latest message, or "" when the language is unclear. */
    fun hint(text: String): String = when (asked(text)) {
        Lang.HINDI_DEVANAGARI -> "(The user asks for Hindi: reply only in Hindi, written in Devanagari, with no English translation.)"
        Lang.ENGLISH -> "(The user asks for English: reply in English.)"
        Lang.HINGLISH -> "(The user asks for Hinglish: reply in Hinglish, Hindi words in Roman script, not English.)"
        else -> byScript(text)
    }

    private fun byScript(text: String): String = when (detect(text)) {
        Lang.HINDI_DEVANAGARI -> "(Reply in Hindi, written in Devanagari.)"
        // Not "English letters": in tests that made Gemma and Qwen answer in English.
        Lang.HINGLISH -> "(Reply in Hinglish, like this message: Hindi words in Roman script, not English.)"
        Lang.ENGLISH -> "(Reply in English.)"
        Lang.UNKNOWN -> ""
    }

    /** [text] with its reply instruction on a new line (unchanged when the language is unclear). */
    fun withHint(text: String): String = hint(text).let { if (it.isEmpty()) text else "$text\n\n$it" }

    /**
     * For a request whose reply has "Picture:" lines for the online image AI: those lines in English
     * (the image AI misreads Hindi: a Hindi "elephant" description gave a picture of a girl), the
     * rest in the user's language.
     */
    fun withPictureHint(text: String): String {
        val reply = when (asked(text) ?: detect(text)) {
            Lang.HINDI_DEVANAGARI -> " Reply to the user in Hindi, written in Devanagari."
            Lang.HINGLISH -> " Reply to the user in Hinglish, like this message: Hindi words in Roman script."
            Lang.ENGLISH -> " Reply in English."
            Lang.UNKNOWN -> ""
        }
        return "$text\n\n(Write every Picture line in English.$reply)"
    }

    /** True when most letters of [text] are not Latin (e.g. Devanagari). */
    fun mostlyNonLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it.code >= 0x250 } * 10 >= letters.length * 3
    }
}
