package com.gravity.ide

import com.gravity.ide.core.TalkMemory
import com.gravity.ide.core.TalkMemory.Fact
import com.gravity.ide.core.TalkMemory.Kind
import com.gravity.ide.core.TalkTopics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkTest {
    @Test fun askingToJustChatIsNoticed() {
        listOf("kuch baat karo", "bore ho raha hun yaar", "koi topic do", "kuch bhi bolo", "let's chat", "I'm bored", "कुछ बात करो", "बोर हो रहा हूँ")
            .forEach { assertTrue(it, TalkTopics.wantsTopic(it)) }
        listOf("India ki capital kya hai", "mujhe ek kahani sunao jo bahut lambi ho aur usme ek raja ho jo jungle me rehta ho", "weather kaisa hai")
            .forEach { assertFalse(it, TalkTopics.wantsTopic(it)) }
    }

    @Test fun manyTopicsFromDifferentAnglesWithoutRepeats() {
        assertTrue("only ${TalkTopics.topicCount} topics", TalkTopics.topicCount >= 300)
        assertTrue(TalkTopics.angles.size >= 10)
        val first = TalkTopics.pick(1).first
        val next = TalkTopics.pick(1, recent = listOf(first)).first
        assertTrue(next != first)
        val hint = TalkTopics.hint("their favourite festival and why", TalkTopics.angles[0])
        assertTrue(hint.contains("Bring up this topic yourself") && hint.contains("favourite festival"))
    }

    @Test fun factsTheUserSaysAboutThemselvesAreRemembered() {
        assertEquals(listOf(Fact(Kind.NAME, "Ravi")), TalkMemory.extract("mera naam Ravi hai"))
        assertEquals(listOf(Fact(Kind.LIKES, "cricket")), TalkMemory.extract("mujhe cricket bahut pasand hai"))
        assertEquals(listOf(Fact(Kind.DISLIKES, "karela")), TalkMemory.extract("mujhe karela bilkul pasand nahi"))
        assertEquals(listOf(Fact(Kind.PLACE, "Patna")), TalkMemory.extract("main patna me rehta hun"))
        assertEquals(listOf(Fact(Kind.PLACE, "Pune"), Fact(Kind.LIKES, "biryani")), TalkMemory.extract("I live in Pune and I love biryani."))
        assertEquals(listOf(Fact(Kind.NAME, "रवि")), TalkMemory.extract("मेरा नाम रवि है"))
        assertEquals(listOf(Fact(Kind.DOES, "neet")), TalkMemory.extract("main NEET ki taiyari kar raha hun"))
        // Questions and "this/that" are not facts.
        assertTrue(TalkMemory.extract("mujhe kya pasand hai?").isEmpty())
        assertTrue(TalkMemory.extract("mujhe ye gaana pasand hai").isEmpty())
        assertTrue(TalkMemory.extract("India ki capital kya hai").isEmpty())
    }

    @Test fun newFactsReplaceOrAdd() {
        var m = TalkMemory.merge(emptyList(), listOf(Fact(Kind.NAME, "Ravi"), Fact(Kind.LIKES, "cricket")))
        m = TalkMemory.merge(m, listOf(Fact(Kind.NAME, "Ravi Kumar"), Fact(Kind.LIKES, "chess"), Fact(Kind.DISLIKES, "cricket")))
        assertEquals(listOf(Fact(Kind.NAME, "Ravi Kumar"), Fact(Kind.LIKES, "chess"), Fact(Kind.DISLIKES, "cricket")), m)
        assertEquals(m, TalkMemory.decode(TalkMemory.encode(m)))
        val p = TalkMemory.forPrompt(m)
        assertTrue(p, p.contains("name: Ravi Kumar") && p.contains("likes: chess") && p.contains("does not like: cricket"))
        assertEquals("", TalkMemory.forPrompt(emptyList()))
    }
}
