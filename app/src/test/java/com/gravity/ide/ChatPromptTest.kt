package com.gravity.ide

import com.gravity.ide.core.ChatPrompt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat prompt lists what Gravity can and cannot do, and is written to build/chat-prompts with
 * real Hindi / Hinglish / English questions, so CI can ask them to phone models (llama.cpp) and
 * show the answers (the "phone-model-chat-check" job).
 */
class ChatPromptTest {
    @Test fun promptSaysWhatGravityCanAndCannotDo() {
        val p = ChatPrompt.system()
        assertTrue(p.contains("What Gravity can do:") && p.contains("What Gravity cannot do:"))
        assertTrue(ChatPrompt.canDo.size >= 5 && ChatPrompt.cannotDo.size >= 5)
        assertTrue(p.contains("Answer the user's latest message only"))
        assertTrue("prompt too long: ${p.length}", p.length < 3500)
        // Phone models read the prompt before their first word: keep theirs short (≈130 tokens).
        val phone = ChatPrompt.phone()
        assertTrue("phone prompt too long: ${phone.length}", phone.length < 1800)
        assertTrue(phone.contains("latest message only") && phone.contains("What Gravity cannot do") && phone.contains("both parts"))
        // The phone prompt keeps the cloud prompt's tested opening and closing rules word for word.
        assertTrue(p.startsWith(phone.substringBefore("\n\nWhat Gravity can do:")))
        assertTrue(p.endsWith(phone.substringAfterLast("\n")))
    }

    @Test fun exportPromptAndQuestionsForThePhoneModelCheck() {
        val dir = File("build/chat-prompts").apply { mkdirs() }
        // The phone-model check asks phone models, so it gets what they get: the short prompt.
        File(dir, "system.txt").writeText(ChatPrompt.forChat(compact = true))
        // As the app sends it to a phone model: each user message carries its reply language.
        fun turn(role: String, text: String) =
            JSONObject().put("role", role).put("content", if (role == "user") com.gravity.ide.core.LanguageHint.withHint(text) else text)
        val capability = "Tum Aisa pura Baat batao ki tum mere liye kya-kya kar sakte ho aur kya-kya Nahin kar sakte ho sab ek-ek line bye line Karke batao"
        val cases = JSONArray()
        // script: the reply's script ("latin" or "devanagari"); hinglish: Hindi words in Roman script.
        fun case(name: String, expectAny: List<String>, vararg turns: JSONObject, script: String? = null, hinglish: Boolean = false) =
            cases.put(
                JSONObject().put("name", name).put("expect_any", JSONArray(expectAny)).put("messages", JSONArray(turns.toList()))
                    .apply { if (script != null) put("script", script); if (hinglish) put("hinglish", true) }
            )
        case("capabilities (user's question)", listOf("नहीं", "nahi", "nahin", "nahee"), turn("user", capability), script = "latin", hinglish = true)
        // The same question right after a poem: small models tend to answer it with another poem.
        case(
            "capabilities after a poem", listOf("नहीं", "nahi", "nahin", "nahee"),
            turn("user", "mere liye ek Hindi mein gana Gao"),
            turn("assistant", "मैं आपकी रात की खुशी में हूँ\nतुम के साथ बहुत जुटका हूँ\nआपकी गाना बजाऊँगा\nतुम से बहुत जुटका हूँ"),
            turn("user", capability), script = "latin", hinglish = true,
        )
        case("Hindi song", listOf(), turn("user", "mere liye ek Hindi mein gana Gao"))
        case("Hindi facts", listOf("दिल्ली", "Delhi"), turn("user", "भारत की राजधानी क्या है? वहाँ घूमने की तीन जगहें बताओ।"), script = "devanagari")
        case("Hinglish advice", listOf(), turn("user", "mujhe kal subah 6 baje uthna hai, koi 3 tips do"), script = "latin", hinglish = true)
        case("English", listOf("light", "sun"), turn("user", "What is photosynthesis? Explain in 2 lines."), script = "latin")
        case("capabilities (English)", listOf("cannot", "can't", "can not", "unable"), turn("user", "What can you do for me, and what can't you do? Tell me line by line."), script = "latin")
        case("capabilities (Devanagari)", listOf("नहीं"), turn("user", "तुम मेरे लिए क्या-क्या कर सकते हो और क्या-क्या नहीं कर सकते? एक-एक लाइन में बताओ।"), script = "devanagari")
        case(
            "English after a Hindi turn", listOf("light", "scatter"),
            turn("user", "भारत की राजधानी क्या है?"), turn("assistant", "भारत की राजधानी नई दिल्ली है।"),
            turn("user", "Now a different question, in English please: why is the sky blue?"), script = "latin",
        )
        case("Hinglish problem", listOf(), turn("user", "Mera phone bahut slow chal raha hai, kya karu?"), script = "latin", hinglish = true)
        case("English short", listOf("Mars", "Jupiter", "Venus", "Saturn", "Mercury", "Neptune", "Uranus"), turn("user", "Name three planets."), script = "latin")
        File(dir, "cases.json").writeText(cases.toString(2))
        // What the app sends a phone model for "ek bandar ki image banao" drawn on the phone (the
        // arm64 real-model check runs it), and what its warm-up sent before (a new chat).
        File(dir, "warm-system.txt").writeText(ChatPrompt.forChat(compact = true))
        File(dir, "draw-system.txt").writeText(
            ChatPrompt.forChat(compact = true, extra = "\n\n" + com.gravity.ide.core.MakeFiles.instruction(com.gravity.ide.core.MakeFiles.Kind.IMAGE, onlinePictures = false))
        )
        File(dir, "draw-question.txt").writeText(com.gravity.ide.core.LanguageHint.withHint("ek bandar ki image banao"))
        File(dir, "draw-tokens.txt").writeText(com.gravity.ide.core.MakeFiles.drawTokens(com.gravity.ide.core.MakeFiles.Kind.IMAGE, 4096).toString())
        assertTrue(File(dir, "cases.json").length() > 100)
    }
}
