package com.gravity.ide

import com.gravity.ide.core.TextQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQualityTest {
    @Test fun readableTextLayersAreKept() {
        assertFalse(TextQuality.pdfTextLooksBroken((1..40).joinToString(" ") { "The school rules say students must arrive on time; uniforms are required." }, 2))
        assertFalse(TextQuality.pdfTextLooksBroken((1..40).joinToString(" ") { "विद्यालय के नियम: सभी छात्र समय पर आएँ और पूरी वर्दी पहनें।" }, 2))
    }

    @Test fun missingOrLegacyFontTextGoesToOcr() {
        assertTrue(TextQuality.pdfTextLooksBroken((1..40).joinToString(" ") { "fo|ky; ds fu;e % lHkh Nk= le; ij vk,a vkSj iwjh onhZ igusaA" }, 2))
        assertTrue(TextQuality.pdfTextLooksBroken("  \n ", 3))
        assertTrue(TextQuality.pdfTextLooksBroken("Page 1", 3))
        assertTrue(TextQuality.pdfTextLooksBroken("��ab�".repeat(100), 1))
    }

    @Test fun ocrLookalikeDigitsAreFixedOnHindiAndEnglishPages() {
        org.junit.Assert.assertEquals("Arrive by 8 AM, room 10", com.gravity.ide.core.TextQuality.fixOcrDigits("Arrive by \u09EA AM, room 1\u09E6"))
        // A real Bengali page keeps its digits.
        val bengali = "\u0986\u09AE\u09BE\u09B0 \u09EA"
        org.junit.Assert.assertEquals(bengali, com.gravity.ide.core.TextQuality.fixOcrDigits(bengali))
    }
}
