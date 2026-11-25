package com.gravity.ide

import com.gravity.ide.core.VoiceStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceStylesTest {
    @Test fun tenStylesWithSafeSettings() {
        assertEquals(13, VoiceStyles.all.size)
        assertEquals(13, VoiceStyles.all.map { it.id }.toSet().size)
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

    @Test fun assistantPersonalitiesGreetAndAddressTheUser() {
        val butler = VoiceStyles.byId("butler")
        assertEquals("Good evening, Sir. All systems are ready. What shall we do?", VoiceStyles.greeting(butler, false, null, 20, false))
        assertEquals("सुप्रभात, Rahul। सब तैयार है। बताइए, क्या करना है?", VoiceStyles.greeting(butler, true, "Rahul", 8, false))
        assertEquals("हाँ, बोलिए! मैं सुन रही हूँ।", VoiceStyles.greeting(VoiceStyles.byId("friend"), true, null, 8, true))
        val p = VoiceStyles.personaFor(butler, null, "female", true)
        org.junit.Assert.assertTrue(p.contains("\"सर\"") && p.contains("कर सकती हूँ") && !p.contains("{you}"))
        val f = VoiceStyles.personaFor(VoiceStyles.byId("friend"), "Boss", "male", false)
        org.junit.Assert.assertTrue(f.contains("Call the user \"Boss\"") && f.contains("कर सकता हूँ"))
    }
}
