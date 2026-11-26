package com.gravity.ide

import com.gravity.ide.core.EmotionClassifier
import com.gravity.ide.core.Expressions
import com.gravity.ide.voice.OnlineVoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The online voice's requests and answers (no network here: the format only). */
class OnlineVoiceTest {
    @Test fun requestAsksForAudioInTheChosenVoice() {
        val b = OnlineVoice.body("Haha, that was fun! 😂", "amused", "Kore", "gemini-3.8-flash-lite-tts")
        val cfg = b.getJSONObject("generationConfig")
        assertEquals("AUDIO", cfg.getJSONArray("responseModalities").getString(0))
        assertEquals("Kore", cfg.getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        val part = b.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        // 3.8 reads the text word for word: the mood goes in speech_metadata, laughter as a tag, no emoji.
        assertEquals("<laugh>, that was fun!", part.getString("text"))
        assertEquals("amused", part.getJSONObject("speech_metadata").getString("style"))
        // Without the style (a model that refused it).
        assertFalse(OnlineVoice.body("Hi", "amused", "Kore", "gemini-3.8-flash-tts", withStyle = false)
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).has("speech_metadata"))
    }

    @Test fun olderModelGetsTagsInTheText() {
        val part = OnlineVoice.body("हाहा, क्या बात है!", "amused", "Puck", "gemini-3.1-flash-tts-preview")
            .getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("[amused] [laughs], क्या बात है!", part.getString("text"))
        // A laughing emoji alone is a laugh.
        assertEquals("That was fun! [laughs]", OnlineVoice.transcript("That was fun! 😂", verbatim = false))
    }

    @Test fun moodsBecomeStyles() {
        assertEquals("", OnlineVoice.style(EmotionClassifier.Result(EmotionClassifier.Emotion.NEUTRAL, 1f, 0f), Expressions.Mode.EXPRESSIVE))
        assertEquals("sad, soft and slow", OnlineVoice.style(EmotionClassifier.Result(EmotionClassifier.Emotion.SAD, 1f, 1f), Expressions.Mode.EXPRESSIVE))
        assertEquals("slightly sad, soft and slow", OnlineVoice.style(EmotionClassifier.Result(EmotionClassifier.Emotion.SAD, 1f, 1f), Expressions.Mode.NORMAL))
        assertEquals("clear, calm and professional", OnlineVoice.style(EmotionClassifier.Result(EmotionClassifier.Emotion.EXCITED, 1f, 1f), Expressions.Mode.PROFESSIONAL))
    }

    @Test fun voicesMatchYaminisGender() {
        assertEquals("female", OnlineVoice.voices.first { it.name == OnlineVoice.defaultVoice("female") }.gender)
        assertEquals("male", OnlineVoice.voices.first { it.name == OnlineVoice.defaultVoice("male") }.gender)
    }

    private fun answer(bytes: ByteArray, mime: String) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(
            JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", java.util.Base64.getEncoder().encodeToString(bytes))),
        )))),
    )

    @Test fun rawPcmAndWavAnswersAreBothRead() {
        val pcm = byteArrayOf(1, 0, 2, 0, -1, -1)
        val (s, rate) = OnlineVoice.audioFrom(answer(pcm, "audio/L16;codec=pcm;rate=24000"))
        assertEquals(24000, rate)
        assertEquals(listOf<Short>(1, 2, -1), s.toList())
        val wav = com.gravity.ide.core.WavJoin.wavMono16(shortArrayOf(5, 6, 7), 16000)
        val (w, wr) = OnlineVoice.audioFrom(answer(wav, "audio/wav"))
        assertEquals(16000, wr)
        assertEquals(listOf<Short>(5, 6, 7), w.toList())
    }

    @Test fun anAnswerWithoutAudioIsAnError() {
        val r = runCatching { OnlineVoice.audioFrom(JSONObject().put("candidates", JSONArray())) }
        assertTrue(r.isFailure)
    }

    @Test fun theFirstPieceIsShortSoItStartsSoon() {
        val text = (1..60).joinToString(" ") { "This is sentence number $it of a long reply." }
        val pieces = OnlineVoice.pieces(text)
        assertTrue(pieces.size > 2)
        assertTrue(pieces[0].length <= 280)
        assertTrue(pieces.drop(1).all { it.length <= 1200 })
        assertEquals(text, pieces.joinToString(" "))
    }
}
