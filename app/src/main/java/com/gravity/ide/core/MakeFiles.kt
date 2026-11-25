package com.gravity.ide.core

/**
 * What kind of file a chat message asks Gravity to make, and how to pull the pieces for it out of
 * the AI's reply (pure Kotlin, unit tested). The files themselves are made on the phone
 * (files/FileMaker.kt): no cloud service, no limits.
 */
object MakeFiles {
    enum class Kind(val label: String) { PDF("PDF"), ZIP("ZIP"), IMAGE("image"), AUDIO("audio") }

    // A word boundary that also works for Hindi letters, on Android (ICU) and on the JVM alike.
    // (Android's regex does not accept the (?U) flag, and \b there is not the same as on the JVM.)
    private const val L = "[\\p{L}\\p{M}\\p{N}]"
    private const val B = "(?:(?<!$L)(?=$L)|(?<=$L)(?!$L))"

    private val make = "(bana|banao|banaa|bana\\s*do|bana\\s*de|bana\\s*ke|banake|banaiye|banaye|बना|create|make|generate|export|convert|save|" +
        "chahiye|chaiye|dedo|de\\s*do|do\\s*na|bhejo|dikhao|draw|design|record|likh\\s*ke\\s*do)"
    private val pdf = Regex("(?i)${B}pdf${B}.*${B}$make|${B}$make.*${B}pdf${B}|${B}as\\s+(a\\s+)?pdf${B}|${B}pdf\\s+(me|mein|में|file)${B}")
    private val zip = Regex("(?i)${B}zip${B}.*${B}$make|${B}$make.*${B}zip${B}|${B}as\\s+(a\\s+)?zip${B}|${B}zip\\s+(me|mein|में|file)${B}")
    private val imageWord = "(image|images|photo|picture|pic|tasveer|tasvir|chitra|poster|logo|drawing|wallpaper|icon|banner|sticker|illustration|तस्वीर|फोटो|चित्र|पोस्टर|लोगो)"
    private val image = Regex("(?i)${B}$imageWord${B}.*${B}$make|${B}$make.*${B}$imageWord${B}")
    private val audioWord = "(audio|mp3|wav|voice\\s*file|awaaz|awaz|aawaz|आवाज़|आवाज|sound\\s*file|recording|voice\\s*note)"
    private val audio = Regex("(?i)${B}$audioWord${B}.*${B}$make|${B}$make.*${B}$audioWord${B}|${B}$audioWord\\s+(me|mein|में)${B}")

    /** The file a message asks for, or null. Checked in this order: ZIP, PDF, audio, image. */
    fun detect(message: String): Kind? {
        val t = message.trim()
        if (t.isEmpty()) return null
        return when {
            zip.containsMatchIn(t) -> Kind.ZIP
            pdf.containsMatchIn(t) -> Kind.PDF
            audio.containsMatchIn(t) -> Kind.AUDIO
            image.containsMatchIn(t) -> Kind.IMAGE
            else -> null
        }
    }

    /** Extra instruction for the AI for this one message, so its reply can be turned into the file. */
    fun instruction(kind: Kind): String = when (kind) {
        Kind.PDF -> "The user wants a PDF. Write the complete content for it (start with a # title line; use ## headings, " +
            "- bullet points and short paragraphs). Gravity saves your reply as a PDF file, so do not say you cannot make files."
        Kind.ZIP -> "The user wants a ZIP file. Put every file in its own fenced code block, and write its file name on the line " +
            "just before the block as: File: name.ext. Gravity packs these files into a ZIP, so do not say you cannot make files."
        Kind.IMAGE -> "The user wants an image. Draw it as ONE complete, detailed, colourful SVG (viewBox=\"0 0 1024 1024\", only " +
            "shapes, gradients and text; no external images or fonts) in a ```svg code block, then add one short sentence about it. " +
            "Gravity turns the SVG into a PNG picture, so do not say you cannot make images."
        Kind.AUDIO -> "The user wants an audio file. Reply with exactly the words to be spoken (no Markdown, no lists, no emojis). " +
            "Gravity reads your reply aloud with the phone's voice and saves it as an audio file, so do not say you cannot make audio."
    }

    private val fence = Regex("```([A-Za-z0-9_+#.-]*)[^\\n]*\\n([\\s\\S]*?)```")
    private val fileLine = Regex("(?im)^\\s*(?:\\*\\*)?(?:file(?:name)?|फ़ाइल|फाइल)\\s*:?\\s*(?:\\*\\*)?\\s*`?([\\w./ -]+\\.[A-Za-z0-9]+)`?\\s*(?:\\*\\*)?\\s*$")

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
