package com.gravity.ide.core

/**
 * What kind of file a chat message asks Gravity to make, and how to pull the pieces for it out of
 * the AI's reply (pure Kotlin, unit tested). The files themselves are made on the phone
 * (files/FileMaker.kt): no cloud service, no limits.
 */
object MakeFiles {
    enum class Kind(val label: String) { PDF("PDF"), ZIP("ZIP"), IMAGE("image"), AUDIO("audio"), VIDEO("video") }

    // A word boundary that also works for Hindi letters, on Android (ICU) and on the JVM alike.
    // (Android's regex does not accept the (?U) flag, and \b there is not the same as on the JVM.)
    private const val L = "[\\p{L}\\p{M}\\p{N}]"
    private const val B = "(?:(?<!$L)(?=$L)|(?<=$L)(?!$L))"

    // Verbs, in English letters and in Devanagari (voice typing writes Hindi in Devanagari: "पीडीएफ बनाओ").
    private val make = "(bana|banao|banaa|bana\\s*do|bana\\s*de|bana\\s*ke|banake|banaiye|banaye|banana|create|make|generate|export|convert|save|" +
        "chahiye|chaiye|dedo|de\\s*do|do\\s*na|bhejo|dikhao|draw|design|record|likh\\s*ke\\s*do|taiyar|tayar|ready\\s*karo|" +
        "बना|जनरेट|क्रिएट|तैयार|चाहिए|चाहिये|भेजो|दिखाओ|ड्रा|डिज़ाइन|डिजाइन|रिकॉर्ड|सेव|कन्वर्ट|एक्सपोर्ट)"
    private val pdfWord = "(pdf|पीडीएफ|पी\\s*डी\\s*एफ)"
    private val zipWord = "(zip|ज़िप|जिप)"
    private val pdf = Regex("(?i)${B}$pdfWord${B}.*${B}$make|${B}$make.*${B}$pdfWord${B}|${B}as\\s+(a\\s+)?pdf${B}|${B}$pdfWord\\s+(me|mein|में|file|फाइल|फ़ाइल)${B}")
    private val zip = Regex("(?i)${B}$zipWord${B}.*${B}$make|${B}$make.*${B}$zipWord${B}|${B}as\\s+(a\\s+)?zip${B}|${B}$zipWord\\s+(me|mein|में|file|फाइल|फ़ाइल)${B}")
    private val imageWord = "(image|images|photo|photos|picture|pic|tasveer|tasvir|chitra|poster|logo|drawing|wallpaper|icon|banner|sticker|illustration|" +
        "तस्वीर|फोटो|फ़ोटो|फोटू|चित्र|पोस्टर|लोगो|इमेज|पिक्चर|ड्रॉइंग|ड्राइंग|वॉलपेपर|वालपेपर|बैनर|स्टिकर|आइकन)"
    private val image = Regex("(?i)${B}$imageWord${B}.*${B}$make|${B}$make.*${B}$imageWord${B}")
    private val audioWord = "(audio|mp3|wav|voice\\s*file|awaaz|awaz|aawaz|आवाज़|आवाज|ऑडियो|sound\\s*file|recording|voice\\s*note|रिकॉर्डिंग)"
    private val audio = Regex("(?i)${B}$audioWord${B}.*${B}$make|${B}$make.*${B}$audioWord${B}|${B}$audioWord\\s+(me|mein|में)${B}")
    private val videoWord = "(video|videos|clip|reel|reels|mp4|animation|वीडियो|विडियो|वीडियोज़|क्लिप|रील|एनिमेशन)"
    private val video = Regex("(?i)${B}$videoWord${B}.*${B}$make|${B}$make.*${B}$videoWord${B}|${B}$videoWord\\s+(me|mein|में)${B}")

    // "video kaise banate hain?" asks how, it is not a request; "kaise ... bana do" still is.
    private val question = Regex("(?i)${B}(kaise|kese|how|what|kya\\s+hota|kya\\s+hai)${B}|कैसे|क्या\\s+होता|क्या\\s+है")
    private val request = Regex(
        "(?i)banao|bana\\s*do|bana\\s*de|banaiye|bana\\s*ke\\s*do|banakar|chahiye|chaiye|bhejo|generate\\s+karo|create\\s+karo|" +
            "${B}(make|create|generate|draw|give)\\s+(me|a|an|the|my)${B}|बनाओ|बना\\s*दो|बना\\s*दीजिए|बनाइए|बना\\s*के\\s*दो|बनाकर|चाहिए|चाहिये|भेजो|जनरेट\\s*करो",
    )

    /** The file a message asks for, or null. Checked in this order: ZIP, PDF, video, audio, image. */
    fun detect(message: String): Kind? {
        val t = message.trim()
        if (t.isEmpty()) return null
        if (question.containsMatchIn(t) && !request.containsMatchIn(t)) return null
        return when {
            zip.containsMatchIn(t) -> Kind.ZIP
            pdf.containsMatchIn(t) -> Kind.PDF
            video.containsMatchIn(t) -> Kind.VIDEO
            audio.containsMatchIn(t) -> Kind.AUDIO
            image.containsMatchIn(t) -> Kind.IMAGE
            else -> null
        }
    }

    /**
     * Words a phone model may write for a drawing in a context of [contextTokens]: a picture up to
     * 2500, a video's scenes up to 4000, always leaving a third of the context for the prompt.
     */
    fun drawTokens(kind: Kind, contextTokens: Int): Int =
        minOf(if (kind == Kind.VIDEO) 4000 else 2500, contextTokens * 2 / 3).coerceAtLeast(256)

    /**
     * Extra instruction for the AI for this one message, so its reply can be turned into the file.
     * With [onlinePictures], pictures come from an online image AI: the AI only describes them.
     */
    fun instruction(kind: Kind, onlinePictures: Boolean = false): String = when {
        onlinePictures && kind == Kind.IMAGE ->
            "The user wants a picture. Gravity makes it with an image AI from your description. Write one line \"Picture: <a vivid, detailed " +
                "description in English for an image AI: the subject, what it is doing, style (for example photo, cartoon, watercolor), colours, " +
                "lighting, background>\" and then one short friendly sentence for the user in their language. Do not draw it yourself, no code."
        onlinePictures && kind == Kind.VIDEO ->
            "The user wants a short video. Gravity makes it on the phone as an MP4: one AI picture per scene, your narration read aloud by the " +
                "phone's voice. Write 3 to 6 scenes. For each scene write a line \"Scene N: <one or two narration sentences in the user's " +
                "language>\" and right after it a line \"Picture: <a vivid, detailed description in English of that scene's picture for an image " +
                "AI, portrait format>\". Keep the same characters and style in every scene. No other text, no code."
        else -> instruction(kind)
    }

    private fun instruction(kind: Kind): String = when (kind) {
        Kind.PDF -> "The user wants a PDF. Write the complete content for it (start with a # title line; use ## headings, " +
            "- bullet points and short paragraphs). Gravity saves your reply as a PDF file, so do not say you cannot make files."
        Kind.ZIP -> "The user wants a ZIP file. Put every file in its own fenced code block, and write its file name on the line " +
            "just before the block as: File: name.ext. Gravity packs these files into a ZIP, so do not say you cannot make files."
        // Tested on Gemma 3 4B (five subjects, rendered and compared): asking first for what makes the
        // subject different and for each part's numbers, plus a simple layout, gave recognisable
        // pictures where "one detailed SVG" gave shapeless ones. The examples are deliberately
        // other subjects than the test's (a worked example drawing was copied instead).
        Kind.IMAGE -> "The user wants a picture. Draw it yourself as SVG code: Gravity turns it into a PNG, so never say you cannot make images.\n" +
            "Write ONE ```svg code block with one complete SVG: <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 400 400\">.\n" +
            "Start inside it with a comment that plans the picture:\n" +
            "1. The features that make this subject different from similar things (for example a giraffe: yellow with brown patches, a very long " +
            "neck, two small horns; a rabbit: long upright ears, a round white tail).\n" +
            "2. Each part with its shape, real colour and numbers on the 400x400 canvas (centre x,y and size), so that parts that belong together " +
            "touch or overlap.\n" +
            "Layout: the background and ground first; the subject big and in the middle, about 250 wide. A living thing has an oval body around " +
            "(200,250) with its head overlapping the top of the body, and legs that start inside the body and end on the ground near y=340. Use " +
            "ellipses and circles for bodies, heads and ears; rects and polygons for buildings, cars and furniture; thick round paths (stroke-width " +
            "10 to 25, stroke-linecap round) for tails, trunks, arms and branches.\n" +
            "Then draw the parts from back to front: background, body, legs and tail, head, ears, face details last.\n" +
            "After the code block write one short sentence about the picture."
        Kind.VIDEO -> "The user wants a short video. Gravity makes it on the phone as an MP4: one picture per scene, your narration read aloud by the " +
            "phone's voice. Write 3 to 6 scenes. For each scene write one line \"Scene N: <one or two narration sentences in the user's language>\" " +
            "and right after it ONE complete, colourful SVG drawing of that scene in a ```svg code block (viewBox=\"0 0 720 1280\", portrait, only " +
            "shapes, gradients and text; no external images or fonts). No other text. Do not say you cannot make videos."
        Kind.AUDIO -> "The user wants an audio file. Reply with exactly the words to be spoken (no Markdown, no lists, no emojis). " +
            "Gravity reads your reply aloud with the phone's voice and saves it as an audio file, so do not say you cannot make audio."
    }

    private val fence = Regex("```([A-Za-z0-9_+#.-]*)[^\\n]*\\n([\\s\\S]*?)```")
    private val fileLine = Regex("(?im)^\\s*(?:\\*\\*)?(?:file(?:name)?|फ़ाइल|फाइल)\\s*:?\\s*(?:\\*\\*)?\\s*`?([\\w./ -]+\\.[A-Za-z0-9]+)`?\\s*(?:\\*\\*)?\\s*$")

    /**
     * The scenes of a video reply: (narration, SVG) pairs, in order. Narration is the "Scene N:" line
     * before each drawing (or the text between drawings).
     */
    fun scenes(reply: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var from = 0
        while (true) {
            val start = reply.indexOf("<svg", from, ignoreCase = true)
            if (start < 0) break
            val end = reply.indexOf("</svg>", start, ignoreCase = true)
            if (end < 0) break
            val before = reply.substring(from, start)
            val narration = before.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") }
                .joinToString(" ")
                .replace(Regex("(?i)^\\**\\s*(scene|दृश्य|सीन)\\s*\\d+\\s*[:.)-]?\\**\\s*"), "")
                .replace("*", "").trim()
            out += narration to reply.substring(start, end + "</svg>".length)
            from = end + "</svg>".length
        }
        return out
    }

    private val pictureLine = Regex("(?im)^\\s*[*_]*\\s*(picture|image|image\\s*prompt|prompt|चित्र|तस्वीर)\\s*[*_]*\\s*[:：]\\s*[*_]*\\s*(.+)$")
    private val sceneLine = Regex("(?i)^\\s*[*_#]*\\s*(scene|दृश्य|सीन)\\s*\\d+\\s*[*_]*\\s*[:.)-]\\s*[*_]*\\s*(.*)$")

    /** The picture description in a reply ("Picture: …"), or null. */
    fun picturePrompt(reply: String): String? =
        pictureLine.find(reply)?.groupValues?.get(2)?.trim()?.trim('*', '_', '"', ' ')?.takeIf { it.length >= 3 }

    /** Video scenes with picture descriptions: (narration, picture description) pairs, in order. */
    fun pictureScenes(reply: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var narration = StringBuilder()
        for (line in reply.lines()) {
            val scene = sceneLine.find(line)
            val pic = pictureLine.find(line)
            when {
                pic != null -> {
                    val prompt = pic.groupValues[2].trim().trim('*', '_', '"', ' ')
                    if (prompt.isNotEmpty()) out += narration.toString().trim() to prompt
                    narration = StringBuilder()
                }
                scene != null -> {
                    narration = StringBuilder(scene.groupValues[2].replace("*", "").trim())
                }
                line.isNotBlank() && narration.isNotEmpty() -> narration.append(' ').append(line.replace("*", "").trim())
            }
        }
        return out
    }

    /** The first SVG in a reply (in a code block or bare), or null. */
    fun svg(reply: String): String? {
        val start = reply.indexOf("<svg", ignoreCase = true)
        val end = reply.lastIndexOf("</svg>", ignoreCase = true)
        if (start < 0 || end < start) return null
        return reply.substring(start, end + "</svg>".length)
    }

    /**
     * Files in a reply's code blocks: the name written before a block ("File: app.js"), or one made
     * from the block's language (index.html, style.css, script.js, main.py, ...). Names are kept
     * inside the ZIP (no "..", no leading "/").
     */
    fun codeFiles(reply: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val used = HashSet<String>()
        var searchFrom = 0
        for (m in fence.findAll(reply)) {
            val before = reply.substring(searchFrom, m.range.first)
            searchFrom = m.range.last + 1
            val lang = m.groupValues[1].lowercase()
            val body = m.groupValues[2]
            val named = fileLine.findAll(before).lastOrNull()?.groupValues?.get(1)?.trim()
            var name = safeName(named ?: defaultName(lang))
            if (!used.add(name.lowercase())) {
                val dot = name.lastIndexOf('.')
                var n = 2
                var candidate: String
                do {
                    candidate = if (dot > 0) name.substring(0, dot) + "-$n" + name.substring(dot) else "$name-$n"
                    n++
                } while (!used.add(candidate.lowercase()))
                name = candidate
            }
            out += name to body
        }
        return out
    }

    private fun safeName(name: String): String {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        return parts.joinToString("/").ifBlank { "file.txt" }
    }

    private fun defaultName(lang: String): String = when (lang) {
        "html", "htm" -> "index.html"
        "css" -> "style.css"
        "js", "javascript" -> "script.js"
        "ts", "typescript" -> "main.ts"
        "py", "python" -> "main.py"
        "kt", "kotlin" -> "Main.kt"
        "java" -> "Main.java"
        "json" -> "data.json"
        "xml" -> "file.xml"
        "sh", "bash", "shell" -> "script.sh"
        "md", "markdown" -> "README.md"
        "svg" -> "image.svg"
        "c" -> "main.c"
        "cpp", "c++" -> "main.cpp"
        "sql" -> "query.sql"
        "yaml", "yml" -> "config.yml"
        else -> if (lang.isNotBlank()) "file.$lang" else "file.txt"
    }

    /** A short file name from the start of a request ("मेरे लिए school rules की PDF" → "school-rules"). */
    fun baseName(text: String, fallback: String): String {
        val words = text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").split(Regex("\\s+"))
            .filter { it.length > 2 && it !in filler }.take(4)
        return words.joinToString("-").take(40).ifBlank { fallback }
    }

    private val filler = setOf(
        "pdf", "zip", "image", "photo", "audio", "file", "banao", "bana", "make", "create", "the", "and", "for", "mere", "liye",
        "mujhe", "ek", "ka", "ki", "ke", "me", "mein", "please", "plz", "with", "aur", "isko", "iska", "isme", "wala", "wali", "chahiye",
    )

    /** A block of a document for the PDF: "h1", "h2", "h3", "li", "code" or "p", with its text (inline Markdown removed). */
    data class Block(val style: String, val text: String)

    private val inlineMarks = Regex("\\*\\*|__|`|~~")
    private val linkMd = Regex("!?\\[([^\\]]*)]\\(([^)]*)\\)")

    private fun inline(t: String) = linkMd.replace(t) { m -> "${m.groupValues[1]} (${m.groupValues[2]})" }.replace(inlineMarks, "").trim()

    fun pdfBlocks(markdown: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += Block("p", inline(para.toString()))
            para.clear()
        }
        val lines = markdown.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    out += Block("code", code.toString().trimEnd())
                }
                t.startsWith("### ") -> { flush(); out += Block("h3", inline(t.removePrefix("### "))) }
                t.startsWith("## ") -> { flush(); out += Block("h2", inline(t.removePrefix("## "))) }
                t.startsWith("# ") -> { flush(); out += Block("h1", inline(t.removePrefix("# "))) }
                Regex("^([-*•]|\\d+[.)])\\s+.*").matches(t) -> {
                    flush()
                    val number = Regex("^(\\d+)[.)]\\s+").find(t)?.groupValues?.get(1)
                    val body = t.replace(Regex("^([-*•]|\\d+[.)])\\s+"), "")
                    out += Block("li", (if (number != null) "$number. " else "• ") + inline(body))
                }
                t.isEmpty() -> flush()
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out.filter { it.text.isNotBlank() }
    }
}
