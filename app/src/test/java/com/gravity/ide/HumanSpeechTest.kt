package com.gravity.ide

import com.gravity.ide.core.HumanSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanSpeechTest {
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test fun everyWordIsSpokenOnceInOrder() {
        val text = "Arre, ye toh bahut achha hai! Main aapke liye ek gaana bana sakti hoon (thoda chhota sa), aur phir hum use sun sakte hain. Kya aap taiyaar hain?"
        val plan = HumanSpeech.plan(text)
        assertEquals(words(text), plan.flatMap { words(it.text) })
    }

    @Test fun theFeelingWordIsDrawnOutAndTheOpeningWordStandsAlone() {
        val plan = HumanSpeech.plan("Arre, ye toh bahut achha hai! Chalo shuru karte hain.")
        val arre = plan.first()
        assertEquals("Arre,", arre.text)
        assertTrue("opening word slower: ${arre.rate}", arre.rate < 0.92f)
        assertTrue("opening word higher: ${arre.pitch}", arre.pitch > 1.03f)
        assertTrue("a pause after it: ${arre.pauseAfterMs}", arre.pauseAfterMs >= 100)
        val stressed = plan.first { it.text.startsWith("bahut achha") }
        assertTrue("stretched: ${stressed.rate}", stressed.rate < 0.88f)
        assertTrue("stressed louder: ${stressed.volume}", stressed.volume >= 0.98f)
    }

    @Test fun hindiInDevanagariToo() {
        val plan = HumanSpeech.plan("अरे, मुझे ये गाना बहुत पसंद आया।")
        assertEquals("अरे,", plan.first().text)
        val stressed = plan.first { it.text.startsWith("बहुत") }
        assertEquals("बहुत पसंद आया।", stressed.text)
        assertTrue(stressed.rate < 0.88f)
    }

    @Test fun questionsRiseAndAsidesAreSofter() {
        val q = HumanSpeech.plan("Aaj mausam kaisa hai, aur kal kya plan hai?").last()
        assertTrue("question rises: ${q.pitch}", q.pitch > 1.03f)
        val aside = HumanSpeech.plan("Main kal aaungi (shayad thodi der se) aur hum baat karenge.").first { it.text.startsWith("(") }
        assertTrue("aside softer: ${aside.volume}", aside.volume < 0.85f)
        assertTrue("aside quicker: ${aside.rate}", aside.rate > 1.03f)
    }

    @Test fun staysInSafeRangesAndIsTheSameEachTime() {
        val text = "Wow! This is really amazing, isn't it? I mean, you know, we did it... Finally."
        val a = HumanSpeech.plan(text)
        assertEquals(a, HumanSpeech.plan(text))
        a.forEach {
            assertTrue(it.rate in 0.7f..1.3f && it.pitch in 0.85f..1.2f && it.volume in 0.6f..1f && it.pauseAfterMs >= 0)
        }
        assertEquals("nothing after the last words", 0, a.last().pauseAfterMs)
        // Not every phrase the same: that is the point.
        assertTrue(a.map { it.rate }.distinct().size >= 3)
    }

    @Test fun longSentencesBreatheBeforeAJoiningWord() {
        val plan = HumanSpeech.plan("Humne subah se bahut saara kaam kiya tha office mein lekin shaam tak sab kuch poora nahi ho paaya tha.")
        assertTrue(plan.any { it.text.startsWith("lekin") })
    }
}
