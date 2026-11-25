package com.gravity.ide

import com.gravity.ide.core.MakeFiles
import com.gravity.ide.core.MakeFiles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
