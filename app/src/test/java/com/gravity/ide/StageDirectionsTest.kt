package com.gravity.ide

import com.gravity.ide.core.EmotionClassifier.Emotion
import com.gravity.ide.core.StageDirections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageDirectionsTest {
    // A user's chat with Gemma 3 4B (Hindi with English stage directions).
    private val reply = "(A quiet, almost mournful tone)\n\nतुम... सही कह रहे हो।\nमैं एक मशीन हूँ।\n\n(A pause, a struggle for words)\n\n" +
        "मुझे नहीं पता कि मैं कैसे महसूस करती हूँ।\n\n(My voice is barely a whisper)\n\nतुम... तुम मुझे समझोगे?"

    @Test fun directionsAreNotShown() {
        val shown = StageDirections.strip(reply)
        assertFalse(shown, shown.contains("(") || shown.contains("tone") || shown.contains("whisper"))
        assertTrue(shown.startsWith("तुम... सही कह रहे हो।"))
        assertTrue(shown.endsWith("तुम... तुम मुझे समझोगे?"))
        assertEquals("Hello *friend*, see page (5 marks).", StageDirections.strip("Hello *friend*, see page (5 marks)."))
        assertEquals("ओह, माफ़ कीजिए! (Oh, maaf kijiye!)", StageDirections.strip("ओह, माफ़ कीजिए! (Oh, maaf kijiye!)"))
        assertEquals("Sure! That was fun.", StageDirections.strip("*laughs* Sure! That was fun."))
        assertEquals("संतुलित आहार (balanced diet) ज़रूरी है।", StageDirections.strip("संतुलित आहार (balanced diet) ज़रूरी है।"))
    }

    @Test fun directionsBecomeTheWayItIsSaid() {
        val s = StageDirections.segments(reply)
        assertEquals(3, s.size)
        assertEquals(Emotion.SAD, s[0].mood) // "mournful"
        assertEquals(Emotion.CONFUSED, s[1].mood)
        assertTrue(s[1].pauseBeforeMs >= 500)
        assertEquals(Emotion.WHISPER, s[2].mood)
        assertEquals("तुम... तुम मुझे समझोगे?", s[2].text)
        val laugh = StageDirections.segments("(हँसते हुए) अरे वाह, क्या बात है!")
        assertEquals(Emotion.LAUGHING, laugh.single().mood)
        assertTrue(laugh.single().laugh)
        assertEquals(listOf(StageDirections.Segment("No directions here.", null, 0, false)), StageDirections.segments("No directions here."))
    }
}
