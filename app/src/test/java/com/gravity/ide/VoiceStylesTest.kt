package com.gravity.ide

import com.gravity.ide.core.VoiceStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceStylesTest {
    @Test fun tenStylesWithSafeSettings() {
        assertEquals(10, VoiceStyles.all.size)
        assertEquals(10, VoiceStyles.all.map { it.id }.toSet().size)
        assertEquals("friend", VoiceStyles.byId(null).id)
        assertEquals(2.0f, VoiceStyles.rate(VoiceStyles.byId("energetic"), 2.0f))
        assertEquals(listOf("Hello dost!", "Kaise ho?", "मैं ठीक हूँ।", "Chalo", "bye"), VoiceStyles.sentences("Hello dost! Kaise ho? मैं ठीक हूँ। Chalo\nbye"))
    }

    @Test fun phoneVoicesAreLabelledMaleOrFemale() {
        assertEquals("female", VoiceStyles.gender("hi-in-x-hia-local"))
        assertEquals("female", VoiceStyles.gender("hi-in-x-hic-network"))
        assertEquals("male", VoiceStyles.gender("hi-in-x-hid-network"))
        assertEquals("male", VoiceStyles.gender("hi-in-x-hie-local"))
        assertEquals("female", VoiceStyles.gender("en-in-x-ena-local"))
        assertEquals("male", VoiceStyles.gender("en-in-x-ene-network"))
        assertEquals("female", VoiceStyles.gender("hin-IND-SMTf00"))
        assertEquals("male", VoiceStyles.gender("en-IN-SMTm01"))
        assertNull(VoiceStyles.gender("en-us-x-sfg-local"))
    }
}
