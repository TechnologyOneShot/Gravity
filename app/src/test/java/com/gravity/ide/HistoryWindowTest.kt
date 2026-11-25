package com.gravity.ide

import com.gravity.ide.core.HistoryWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWindowTest {

    private fun startFor(count: Int, size: Int, budget: Int, step: Int): Int =
        HistoryWindow.start(List(count) { size }, List(count) { it % 2 == 0 }, budget, step)

    @Test fun aShortChatKeepsEverything() {
        assertEquals(0, startFor(10, 100, 3000, 1000))
    }

    @Test fun theStartStaysPutAsTheChatGrows() {
        // 100-token messages, room for 2800, fixed points every 1000.
        var changes = 0
        var previous = 0
        for (count in 1..200) {
            val s = startFor(count, 100, 2800, 1000)
            val kept = (count - s) * 100
            assertTrue("kept $kept tokens > budget at $count messages", kept <= 2800)
            assertTrue("must start at a user message ($s)", s % 2 == 0)
            if (s != previous) changes++
            assertTrue("start went backwards", s >= previous)
            previous = s
        }
        // A sliding window would change the start on almost every one of the ~170 replies after
        // the chat filled up; with fixed points it changes only about every 10 messages.
        assertTrue("start changed $changes times", changes in 10..25)
    }

    @Test fun afterAMoveAboutHalfTheChatIsStillKept() {
        val s = startFor(60, 100, 2800, 1000)
        val kept = (60 - s) * 100
        assertTrue("kept only $kept tokens", kept >= 1500)
    }

    @Test fun oneHugeMessageIsStillSent() {
        // The last message alone is bigger than the budget: it is kept (the caller trims files).
        assertEquals(2, HistoryWindow.start(listOf(100, 100, 9000), listOf(true, false, true), 3000, 1000))
    }

    @Test fun hindiCountsMoreTokensThanEnglish() {
        val english = HistoryWindow.tokens("What is the capital of France?")
        val hindi = HistoryWindow.tokens("फ्रांस की राजधानी क्या है?")
        assertTrue("english $english, hindi $hindi", hindi > english)
        // Safe side: Devanagari is counted as a token per letter.
        assertTrue(hindi >= "फ्रांस की राजधानी क्या है?".count { it.code >= 0x250 })
    }
}
