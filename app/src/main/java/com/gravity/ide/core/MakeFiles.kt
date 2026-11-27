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
    private val imageWord = "(image|images|photo|photos|picture|pic|png|jpg|jpeg|tasveer|tasvir|chitra|poster|logo|drawing|wallpaper|icon|banner|sticker|illustration|" +
        "तस्वीर|फोटो|फ़ोटो|फोटू|चित्र|पोस्टर|लोगो|इमेज|पिक्चर|ड्रॉइंग|ड्राइंग|वॉलपेपर|वालपेपर|बैनर|स्टिकर|आइकन)"
    // Also "image do", "png do", "photo dijiye" (asked for a user's chat: the phone model answered
    // "image do" with Python code and "tum png do" with a made-up image link).
    private val image = Regex(
        "(?i)${B}$imageWord${B}.*${B}$make|${B}$make.*${B}$imageWord${B}|${B}$imageWord\\s+(do|de|dena|dijiye|dijie|dikha\\s*do)${B}",
    )
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

    // Asking for the last picture again or changed: "fir se banao", "ek aur", "dobara", "again".
    private val again = Regex(
        "(?i)${B}(fir\\s*se|phir\\s*se|dobara|dubara|ek\\s+aur|aur\\s+ek|again|another|redo|one\\s+more|badlo|badal\\s*do|change\\s+it|" +
            "banao|bana\\s*do|bana\\s*de|bnao|bna\\s*do)${B}|फिर\\s*से|दोबारा|एक\\s+और|बदलो|बदल\\s*दो|बनाओ|बना\\s*दो",
    )
    // What a picture shows: who, how they look, what they do, where, in which style.
    private val describes = Regex(
        "(?i)${B}(hoga|hogi|honge|wala|wali|wale|rang|color|colour|background|smile|smiling|muskura\\w*|hans\\w*|saal|years?\\s*old|umar|age|" +
            "pehne|pehna|pehni|kapde|dress|shirt|baal|hair|chashma|glasses|topi|cap|hat|kitab|book|padh\\w*|likh\\w*|khel\\w*|baith\\w*|khada|khadi|" +
            "school|park|garden|room|kamre|kamra|raat|night|subah|shaam|barish|rain|cartoon|anime|realistic|sketch|painting|bada|badi|chhota|chhoti|" +
            "sundar|cute|ladka|ladki|boy|girl|man|woman|aadmi|aurat|bachcha|baby|dog|cat|kutta|billi|ghoda|horse)${B}|" +
            "होगा|होगी|वाला|वाली|रंग|साल|पहने|किताब|पढ़|खेल|बैठ|लड़का|लड़की|बच्चा|कार्टून",
    )
    private val otherAsk = Regex(
        "(?i)${B}(kahani|story|kavita|poem|gaana|gana|song|lyrics|code|app|website|list|essay|nibandh|letter|patra|quiz|joke|chutkula)${B}|कहानी|कविता|गाना|निबंध|पत्र",
    )
    private val thanks = Regex("(?i)^\\s*(ok|okay|thik|theek|thanks|thank\\s*you|thx|nice|good|accha|achha|acha|badhiya|wow|great|super|haan|ha|yes|no|nahi)\\b")

    /**
     * True when [message] asks again for the picture asked for just before, or changes it, without
     * the word "picture": "dikha hi nahi fir se banao", "wo padh raha hoga, wo 10 years old hoga".
     * [earlier] are the user's earlier messages in the chat, newest last. Without this, a phone model
     * answered such a message itself and pretended to make the picture.
     */
    fun pictureFollowUp(message: String, earlier: List<String>): Boolean {
        val t = message.trim()
        if (t.isEmpty() || detect(t) != null || otherAsk.containsMatchIn(t)) return false
        val recent = earlier.takeLast(3)
        if (recent.none { detect(it) == Kind.IMAGE }) return false
        if (t.contains('?') || (question.containsMatchIn(t) && !again.containsMatchIn(t))) return false
        if (again.containsMatchIn(t)) return true
        // Right after a picture request: a short description (not a question, not a "thanks").
        return detect(recent.last()) == Kind.IMAGE && t.split(Regex("\\s+")).size <= 25 && !thanks.containsMatchIn(t) && describes.containsMatchIn(t)
    }

    /** The picture request a follow-up changes, joined with it ("ek boy ka image banao. wo padh raha hoga…"). */
    fun followUpRequest(message: String, earlier: List<String>): String =
        (earlier.takeLast(3).lastOrNull { detect(it) == Kind.IMAGE }?.let { "$it. " } ?: "") + message.trim()

    // A reply that says it made or shows a picture ("simulating image generation", "यह दूसरी तस्वीर है").
    private val claimsPictureRe = Regex(
        "(?i)simulat\\w*\\s+(the\\s+)?(image|picture|photo)|here\\s+(is|'s)\\s+(a|the|your)\\s+(new\\s+|second\\s+|another\\s+)?(picture|image|photo)|" +
            "i('ve|\\s+have)\\s+(made|created|generated|drawn)\\s+(a|the|an|your)\\s+(new\\s+)?(picture|image|photo)|" +
            "(यह|ये)\\s+(दूसरी\\s+|नई\\s+|आपकी\\s+)?तस्वीर\\s+है|यहाँ\\s+.{0,40}तस्वीर\\s+है|तस्वीर\\s+बना\\s+(दी|ली)",
    )

    // A picture shown as a link to nowhere: ![Elephant eating fruits](https://example.com/…).
    private val madeUpImageLink = Regex("(?i)!\\[[^\\]]*]\\(\\s*(https?://)?(www\\.)?(example\\.(com|org|net)|placeholder|via\\.placeholder|your-image|image-url|link-to)")
    private val imageLink = Regex("!\\[[^\\]]*]\\(\\s*https?://")

    /**
     * True when a reply claims a picture although none was made for its message: words that say so,
     * or a made-up image link. [phoneModel]: any image link counts (a phone model has no internet).
     */
    fun claimsPicture(reply: String, phoneModel: Boolean = false): Boolean =
        claimsPictureRe.containsMatchIn(reply) || madeUpImageLink.containsMatchIn(reply) || (phoneModel && imageLink.containsMatchIn(reply))

    /** The note added to such a reply, so it is not taken for a real picture. */
    const val NO_PICTURE_NOTE =
        "_(No picture was made for this message: the chat AI cannot make or show pictures by itself. Ask for one, e.g. \"ek photo banao…\", and Yamini makes it.)_"

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
     * How to describe a picture for the image AI: a realistic photo unless asked otherwise, with the
     * pose, the camera shot, a real background and the light (a bare "a monkey" gives a plain picture).
     */
    private const val PICTURE_DESCRIPTION =
        "Each picture description is in English, 40 to 80 words. Unless the user asks for another style (cartoon, anime, painting, " +
            "sketch), make it a realistic photo. Include: the subject and how it looks; its pose, action and expression; the camera shot and " +
            "angle (for example a full-body shot at eye level, a close-up portrait, a wide shot); a detailed real background that fits (the " +
            "place, objects, weather, time of day); the lighting (for example soft golden-hour sunlight); and quality words (photorealistic, " +
            "sharp focus, natural colours, detailed textures). Keep every detail the user asked for (the place, the people, what they do). " +
            "No text, letters or watermarks in the picture."

    /**
     * Extra instruction for the AI for this one message, so its reply can be turned into the file.
     * With [onlinePictures], pictures come from an online image AI: the AI only describes them.
     */
    fun instruction(kind: Kind, onlinePictures: Boolean = false): String = when {
        onlinePictures && kind == Kind.IMAGE ->
            "The user wants a picture. Yamini makes it with an image AI from your description, so do not draw it yourself and write no code. " +
                "Write one line \"Picture: <description>\" and then one short friendly sentence for the user.\n" + PICTURE_DESCRIPTION
        onlinePictures && kind == Kind.VIDEO ->
            "The user wants a short video. Yamini makes it on the phone as an MP4: one AI picture per scene, your narration read aloud by the " +
                "phone's voice. Write 3 to 6 scenes. For each scene write a line \"Scene N: <one or two narration sentences in the user's " +
                "language>\" and right after it a line \"Picture: <a vivid, detailed description in English of that scene's picture for an image " +
                "AI, portrait format>\". Keep the same characters and style in every scene. No other text, no code.\n" + PICTURE_DESCRIPTION
        else -> instruction(kind)
    }

    private fun instruction(kind: Kind): String = when (kind) {
        Kind.PDF -> "The user wants a PDF. Write the complete content for it (start with a # title line; use ## headings, " +
            "- bullet points and short paragraphs). Yamini saves your reply as a PDF file, so do not say you cannot make files."
        Kind.ZIP -> "The user wants a ZIP file. Put every file in its own fenced code block, and write its file name on the line " +
            "just before the block as: File: name.ext. Yamini packs these files into a ZIP, so do not say you cannot make files."
        // Tested on Gemma 3 4B (five subjects, rendered and compared): asking first for what makes the
        // subject different and for each part's numbers, plus a simple layout, gave recognisable
        // pictures where "one detailed SVG" gave shapeless ones. The examples are deliberately
        // other subjects than the test's (a worked example drawing was copied instead).
        Kind.IMAGE -> "The user wants a picture. Draw it yourself as SVG code: Yamini turns it into a PNG, so never say you cannot make images.\n" +
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
        Kind.VIDEO -> "The user wants a short video. Yamini makes it on the phone as an MP4: one picture per scene, your narration read aloud by the " +
            "phone's voice. Write 3 to 6 scenes. For each scene write one line \"Scene N: <one or two narration sentences in the user's language>\" " +
            "and right after it ONE complete, colourful SVG drawing of that scene in a ```svg code block (viewBox=\"0 0 720 1280\", portrait, only " +
            "shapes, gradients and text; no external images or fonts). No other text. Do not say you cannot make videos."
        Kind.AUDIO -> "The user wants an audio file. Reply with exactly the words to be spoken (no Markdown, no lists, no emojis). " +
            "Yamini reads your reply aloud with the phone's voice and saves it as an audio file, so do not say you cannot make audio."
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
