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
        assertTrue("prompt too long for a phone model: ${p.length}", p.length < 3500)
    }

    @Test fun exportPromptAndQuestionsForThePhoneModelCheck() {
        val dir = File("build/chat-prompts").apply { mkdirs() }
        File(dir, "system.txt").writeText(ChatPrompt.system())
        fun turn(role: String, text: String) = JSONObject().put("role", role).put("content", text)
        val capability = "Tum Aisa pura Baat batao ki tum mere liye kya-kya kar sakte ho aur kya-kya Nahin kar sakte ho sab ek-ek line bye line Karke batao"
        val cases = JSONArray()
        fun case(name: String, expectAny: List<String>, vararg turns: JSONObject) =
            cases.put(JSONObject().put("name", name).put("expect_any", JSONArray(expectAny)).put("messages", JSONArray(turns.toList())))
        case("capabilities (user's question)", listOf("नहीं", "nahi", "nahin", "nahee"), turn("user", capability))
        // The same question right after a poem: small models tend to answer it with another poem.
        case(
            "capabilities after a poem", listOf("नहीं", "nahi", "nahin", "nahee"),
            turn("user", "mere liye ek Hindi mein gana Gao"),
            turn("assistant", "मैं आपकी रात की खुशी में हूँ\nतुम के साथ बहुत जुटका हूँ\nआपकी गाना बजाऊँगा\nतुम से बहुत जुटका हूँ"),
            turn("user", capability),
        )
        case("Hindi song", listOf(), turn("user", "mere liye ek Hindi mein gana Gao"))
        case("Hindi facts", listOf("दिल्ली", "Delhi"), turn("user", "भारत की राजधानी क्या है? वहाँ घूमने की तीन जगहें बताओ।"))
        case("Hinglish advice", listOf(), turn("user", "mujhe kal subah 6 baje uthna hai, koi 3 tips do"))
        case("English", listOf("light", "sun"), turn("user", "What is photosynthesis? Explain in 2 lines."))
        File(dir, "cases.json").writeText(cases.toString(2))
        assertTrue(File(dir, "cases.json").length() > 100)
    }
}
