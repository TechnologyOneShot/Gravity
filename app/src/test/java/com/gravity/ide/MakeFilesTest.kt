package com.gravity.ide

import com.gravity.ide.core.MakeFiles
import com.gravity.ide.core.MakeFiles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MakeFilesTest {
    @Test fun detectsFileRequestsInHindiHinglishAndEnglish() {
        assertEquals(Kind.PDF, MakeFiles.detect("school ke rules ki pdf banao"))
        assertEquals(Kind.PDF, MakeFiles.detect("make a PDF of this summary"))
        assertEquals(Kind.PDF, MakeFiles.detect("इसकी pdf बना दो"))
        assertEquals(Kind.ZIP, MakeFiles.detect("is app ki zip file bana do"))
        assertEquals(Kind.IMAGE, MakeFiles.detect("ek sunset ki image banao"))
        assertEquals(Kind.IMAGE, MakeFiles.detect("दिवाली का पोस्टर बनाओ"))
        assertEquals(Kind.AUDIO, MakeFiles.detect("is kahani ka audio bana do"))
        // Voice typing writes Hindi in Devanagari: these were missed before.
        assertEquals(Kind.PDF, MakeFiles.detect("मेरे लिए एक पीडीएफ बनाओ"))
        assertEquals(Kind.PDF, MakeFiles.detect("पीडीएफ फाइल बना दो स्कूल के नियम की"))
        assertEquals(Kind.IMAGE, MakeFiles.detect("एक इमेज बनाओ शेर की"))
        assertEquals(Kind.IMAGE, MakeFiles.detect("इमेज जनरेट करो"))
        assertEquals(Kind.ZIP, MakeFiles.detect("जिप फाइल बना दो"))
        assertEquals(Kind.VIDEO, MakeFiles.detect("ek video banao diwali par"))
        assertEquals(Kind.AUDIO, MakeFiles.detect("ऑडियो बना दो"))
        assertEquals(null, MakeFiles.detect("image kya hota hai"))
        assertEquals(Kind.AUDIO, MakeFiles.detect("make an mp3 of this poem"))
    }

    @Test fun ordinaryQuestionsAreNotFileRequests() {
        assertNull(MakeFiles.detect("is pdf ko padho aur batao"))
        assertNull(MakeFiles.detect("isko batao"))
        assertNull(MakeFiles.detect("music player kaise kaam karta hai?"))
        assertNull(MakeFiles.detect("image kya hota hai"))
    }

    @Test fun repliesAreSplitIntoFiles() {
        val reply = "Here you go.\nFile: index.html\n```html\n<html></html>\n```\n**File:** `app/main.js`\n```js\nconsole.log(1)\n```\n```css\nbody{}\n```\n" +
            "```html\n<p>2</p>\n```\nFile: ../../etc/evil.sh\n```\nx\n```"
        assertEquals(listOf("index.html", "app/main.js", "style.css", "index-2.html", "etc/evil.sh"), MakeFiles.codeFiles(reply).map { it.first })
        assertEquals("<svg viewBox=\"0 0 10 10\"><circle r=\"4\"/></svg>", MakeFiles.svg("Sure!\n```svg\n<svg viewBox=\"0 0 10 10\"><circle r=\"4\"/></svg>\n```"))
        assertNull(MakeFiles.svg("no picture"))
        assertEquals("school-rules", MakeFiles.baseName("school rules ki pdf banao", "document"))
    }

    @Test fun aPictureAskedAgainOrChangedIsAPicture() {
        val before = listOf("ek boy ka image banao")
        // Real messages from a user's chat after a phone picture.
        assertTrue(MakeFiles.pictureFollowUp("wo padh raha hoga wo 10 years old hoga", before))
        assertTrue(MakeFiles.pictureFollowUp("dikha hi nahi fir se banao", before + "wo padh raha hoga wo 10 years old hoga"))
        assertTrue(MakeFiles.pictureFollowUp("ab cartoon wala bana do", before))
        assertEquals("ek boy ka image banao. wo padh raha hoga wo 10 years old hoga", MakeFiles.followUpRequest("wo padh raha hoga wo 10 years old hoga", before))
        // Not picture follow-ups: thanks, questions, other requests, or no picture before.
        assertFalse(MakeFiles.pictureFollowUp("thank you, bahut sundar hai", before))
        assertFalse(MakeFiles.pictureFollowUp("ye ladka kaun hai?", before))
        assertFalse(MakeFiles.pictureFollowUp("ab ek kahani bana do", before))
        assertFalse(MakeFiles.pictureFollowUp("tum kaun ho", before))
        assertFalse(MakeFiles.pictureFollowUp("fir se banao", listOf("mujhe GK ke sawal do")))
    }

    @Test fun aReplyThatPretendsToMakeAPictureIsSpotted() {
        assertTrue(MakeFiles.claimsPicture("मैं खुद ही तस्वीर बनाऊँगी।\n... (Processing - simulating image generation) ...\nHere is a picture of a 10-year-old boy studying."))
        assertTrue(MakeFiles.claimsPicture("यह दूसरी तस्वीर है। (Yah doosri tasveer hai.)"))
        assertFalse(MakeFiles.claimsPicture("Photosynthesis is how plants make food from sunlight."))
        assertFalse(MakeFiles.claimsPicture("Aap photo ke liye \"ek photo banao\" likh sakte hain."))
    }
}
