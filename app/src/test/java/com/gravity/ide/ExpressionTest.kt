package com.gravity.ide

import com.gravity.ide.core.EmotionClassifier
import com.gravity.ide.core.EmotionClassifier.Emotion
import com.gravity.ide.core.Expressions
import com.gravity.ide.core.Expressions.Mode
import com.gravity.ide.core.SpeechPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionTest {
    private fun mood(reply: String, user: String? = null) = EmotionClassifier.classify(reply, user).emotion

    @Test fun ordinaryAnswersStayNeutral() {
        listOf(
            "Paris is the capital of France.",
            "भारत की राजधानी नई दिल्ली है।",
            "Python mein list banane ke liye square brackets use karte hain.",
            "The meeting is at 4 pm on Monday.",
        ).forEach { assertEquals(it, Emotion.NEUTRAL, mood(it)) }
    }

    @Test fun clearMoodsAreFound() {
        assertEquals(Emotion.LAUGHING, mood("Hahaha, that's a good one! 😂"))
        assertEquals(Emotion.LAUGHING, mood("हा हा, क्या बात कही!"))
        assertEquals(Emotion.EXCITED, mood("Wow, that's amazing! You did it!"))
        assertEquals(Emotion.HAPPY, mood("Bahut badhiya, mujhe khushi hui."))
        assertEquals(Emotion.SERIOUS, mood("Please see a doctor today; chest pain can be an emergency."))
        assertEquals(Emotion.APOLOGETIC, mood("Sorry, that was my mistake."))
        assertEquals(Emotion.SLEEPY, mood("Good night, so jao ab. 😴"))
        assertEquals(Emotion.WHISPER, mood("Psst… kisi ko mat batana 🤫"))
        assertTrue(mood("मुझे बहुत दुख हुआ यह सुनकर। 😢") == Emotion.SAD)
    }

    @Test fun theUsersFeelingSetsAGentleVoice() {
        // A plain reply to a sad message is said gently, not cheerfully.
        assertEquals(Emotion.EMPATHETIC, mood("Main tumhari baat sun rahi hoon.", "aaj mera mann bahut udaas hai"))
        assertEquals(Emotion.CALM, mood("Theek hai, ek kaam karte hain.", "mujhe bahut gussa aa raha hai 😡"))
    }

    @Test fun moodsCarryOnInsteadOfJumping() {
        val sad = EmotionClassifier.Result(Emotion.SAD, 0.8f, 0.7f)
        val weakHappy = EmotionClassifier.Result(Emotion.HAPPY, 0.4f, 0.35f)
        assertEquals(Emotion.SAD, EmotionClassifier.continueFrom(sad, weakHappy).emotion)
        val neutral = EmotionClassifier.Result(Emotion.NEUTRAL, 1f, 0f)
        val next = EmotionClassifier.continueFrom(sad, neutral)
        assertEquals(Emotion.SAD, next.emotion)
        assertTrue(next.intensity < sad.intensity)
        val strongHappy = EmotionClassifier.Result(Emotion.HAPPY, 0.9f, 0.8f)
        assertEquals(Emotion.HAPPY, EmotionClassifier.continueFrom(sad, strongHappy).emotion)
    }

    @Test fun everyMoodHasADeliveryAndHonestLimits() {
        Emotion.entries.forEach { e ->
            val p = Expressions.full(e)
            assertTrue(e.name, p.rate in 0.7f..1.3f && p.pitch in 0.8f..1.25f && p.volume in 0.3f..1f)
            assertTrue(e.name, Expressions.Capability.SPEED in p.capabilities)
        }
        // What a phone voice cannot do is said, not pretended.
        assertTrue(Expressions.full(Emotion.SAD).limit.contains("No real crying"))
        assertTrue(Expressions.full(Emotion.WHISPER).limit.contains("Not a true whisper"))
        assertTrue(Expressions.full(Emotion.LAUGHING).limit.isNotEmpty())
        // Sad and serious are slower than neutral; happy is quicker.
        val n = Expressions.full(Emotion.NEUTRAL)
        assertTrue(Expressions.full(Emotion.SAD).rate < n.rate)
        assertTrue(Expressions.full(Emotion.SERIOUS).rate < n.rate)
        assertTrue(Expressions.full(Emotion.HAPPY).rate > n.rate)
    }

    @Test fun modesScaleTheFeeling() {
        val sadNormal = Expressions.profile(Emotion.SAD, 1f, Mode.NORMAL)
        val sadExpressive = Expressions.profile(Emotion.SAD, 1f, Mode.EXPRESSIVE)
        val sadWork = Expressions.profile(Emotion.SAD, 1f, Mode.PROFESSIONAL)
        assertTrue(sadExpressive.rate < sadNormal.rate && sadNormal.rate < sadWork.rate)
        assertTrue(Expressions.Capability.BREATH_SOUND in sadExpressive.capabilities)
        assertFalse(Expressions.Capability.BREATH_SOUND in sadNormal.capabilities)
    }

    private val neutral = EmotionClassifier.Result(Emotion.NEUTRAL, 1f, 0f)

    @Test fun anOrdinaryReplyIsReadInOneGoInNormalMode() {
        // The phone's voice reads whole text best (cutting sentences into pieces sounded choppy).
        val plan = SpeechPlanner.plan("Paris is the capital of France. It is on the Seine. It is famous for the Eiffel Tower.", neutral, Mode.NORMAL, false)
        assertEquals(1, plan.size)
        assertEquals("Paris is the capital of France. It is on the Seine. It is famous for the Eiffel Tower.", plan[0].text)
        assertEquals(1f, plan[0].rate)
    }

    @Test fun sentencesAreNeverCutInTheMiddle() {
        val text = "Arre, ye toh bahut achha hai, sach mein! Main aapke liye ek gaana bana sakti hoon, aur phir hum use sun sakte hain."
        val sad = EmotionClassifier.Result(Emotion.SAD, 0.9f, 1f)
        val plan = SpeechPlanner.plan(text, sad, Mode.EXPRESSIVE, true) { true }
        assertEquals(listOf("Arre, ye toh bahut achha hai, sach mein!", "Main aapke liye ek gaana bana sakti hoon, aur phir hum use sun sakte hain."), plan.map { it.text })
    }

    @Test fun hindiAndEnglishSentencesGetTheirOwnVoice() {
        val plan = SpeechPlanner.plan("Hello, कैसे हो? Let me check this. एक मिनट, I'm thinking.", neutral, Mode.NORMAL, true)
        assertEquals(listOf(true, false, true), plan.map { it.hindi })
        assertEquals("Let me check this.", plan[1].text)
        // Hinglish in Roman letters goes to the Hindi voice (the caller says which sentences are Hinglish).
        val mixed = SpeechPlanner.plan("Mujhe yeh samajh nahi aaya. Can you explain again?", neutral, Mode.NORMAL, false) { it.startsWith("Mujhe") }
        assertEquals(listOf(true, false), mixed.map { it.hindi })
    }

    @Test fun laughterIsSpokenAndEmojiAreNotRead() {
        val plan = SpeechPlanner.plan("Hahaha, good one! 😂 Okay, back to work. 👍", neutral, Mode.NORMAL, false)
        assertTrue(plan.first().laugh)
        assertTrue(plan.first().text, plan.first().text.startsWith("ha ha"))
        assertFalse(plan.any { it.text.contains("😂") || it.text.contains("👍") })
        // "Haha … 😂" laughs once, not twice.
        assertEquals(1, plan.count { it.laugh })
        assertEquals("ha ha", SpeechPlanner.plan("😂", neutral, Mode.NORMAL, false).single().text)
        val hindi = SpeechPlanner.plan("हाहा, क्या बात है! 😂", neutral, Mode.NORMAL, true)
        assertTrue(hindi.first().text, hindi.first().text.startsWith("हा हा"))
    }

    @Test fun expressiveModeAddsPausesAndBreathsBetweenWholeSentences() {
        val text = "I am so sorry to hear that. Losing someone you love is one of the hardest things a person can go through in life. " +
            "Take your time. I am right here with you, and you can talk to me whenever you want to."
        val sad = EmotionClassifier.Result(Emotion.SAD, 0.9f, 1f)
        val plan = SpeechPlanner.plan(text, sad, Mode.EXPRESSIVE, false)
        assertEquals(4, plan.size)
        assertTrue(plan.dropLast(1).all { it.pauseAfterMs >= 400 })
        assertEquals(0, plan.last().pauseAfterMs)
        assertTrue(plan.all { it.rate < 0.9f && it.volume < 0.9f })
        assertFalse(plan.first().breathBefore)
        assertTrue(plan.any { it.breathBefore })
        // No breath sounds outside Expressive mode.
        assertFalse(SpeechPlanner.plan(text, sad, Mode.NORMAL, false).any { it.breathBefore })
    }

    @Test fun professionalModeIsEvenAndClear() {
        val plan = SpeechPlanner.plan("Step one is done. Step two is next. Wow, amazing!", EmotionClassifier.Result(Emotion.EXCITED, 0.9f, 1f), Mode.PROFESSIONAL, false)
        assertEquals(3, plan.size)
        assertTrue(plan.dropLast(1).all { it.pauseAfterMs >= 160 })
        assertTrue(plan.all { it.rate < 1.05f && it.pitch < 1.05f })
    }

    @Test fun listeningFillersHaveNoGender() {
        // "समझ रहा हूँ" would be wrong for a woman's voice: the fillers avoid gendered verbs.
        (0..9).forEach { i ->
            val f = SpeechPlanner.listeningFiller(true, i)
            assertFalse(f, f.contains("रहा") || f.contains("रही"))
        }
    }
}
