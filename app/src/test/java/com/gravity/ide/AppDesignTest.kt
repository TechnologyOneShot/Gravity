package com.gravity.ide

import com.gravity.ide.agent.AppBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDesignTest {
    @Test fun designPicturesTravelWithTheInstructionButNotInTheRequest() {
        val instruction = AppBuilder.withDesigns(AppBuilder.buildInstruction("login screen app"), listOf("design/design-1-1.jpg", "design/design-1-2.png"))
        assertEquals(listOf("design/design-1-1.jpg", "design/design-1-2.png"), AppBuilder.designsFrom(instruction))
        assertEquals("login screen app", AppBuilder.requestFrom(instruction))
        assertTrue(AppBuilder.designsFrom(AppBuilder.withDesigns("x", listOf("../secret.jpg"))).isEmpty())
        assertTrue(AppBuilder.designsFrom(AppBuilder.buildInstruction("no design")).isEmpty())
    }

    @Test fun thePromptAsksToCopyTheDesign() {
        val (_, withPicture) = AppBuilder.directPrompt("login app", null, compact = false, designImages = 1)
        assertTrue(withPicture.contains("design picture") && withPicture.contains("hex"))
        val (_, phone) = AppBuilder.directPrompt("login app", null, compact = true, designText = "\"Login\" — 18% from the top, center, large")
        assertTrue(phone.contains("\"Login\" — 18% from the top"))
        val (_, plain) = AppBuilder.directPrompt("login app", null)
        assertFalse(plain.contains("design picture"))
    }

    @Test fun attachedFilesAreAddedWithinALimit() {
        val r = AppBuilder.withReferences("quiz app", listOf("questions.txt" to "Q1?\nQ2?", "big.txt" to "x".repeat(20_000)), maxChars = 1000)
        assertTrue(r.startsWith("quiz app") && r.contains("Attached file questions.txt") && r.contains("Q1?"))
        assertTrue(r.length < 1400)
    }
}
