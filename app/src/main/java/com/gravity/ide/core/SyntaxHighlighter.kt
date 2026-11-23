package com.gravity.ide.core

import java.util.regex.Matcher
import java.util.regex.Pattern

enum class Language(val displayName: String, val extensions: Set<String>) {
    KOTLIN("Kotlin", setOf("kt", "kts")),
    JAVA("Java", setOf("java")),
    PYTHON("Python", setOf("py")),
    JAVASCRIPT("JavaScript", setOf("js", "mjs", "cjs", "jsx")),
    TYPESCRIPT("TypeScript", setOf("ts", "tsx")),
    HTML("HTML", setOf("html", "htm")),
    CSS("CSS", setOf("css", "scss")),
    JSON("JSON", setOf("json")),
    XML("XML", setOf("xml", "svg")),
    YAML("YAML", setOf("yml", "yaml")),
    MARKDOWN("Markdown", setOf("md", "markdown")),
    GRADLE("Gradle", setOf("gradle")),
    C("C", setOf("c", "h")),
    CPP("C++", setOf("cpp", "cc", "cxx", "hpp", "hh")),
    DART("Dart", setOf("dart")),
    SHELL("Shell", setOf("sh", "bash", "zsh")),
    SQL("SQL", setOf("sql")),
    PLAIN("Plain text", emptySet());

    companion object {
        fun forFileName(name: String): Language {
            val lower = name.lowercase()
            if (lower.endsWith(".gradle.kts")) return KOTLIN
            if (lower == "dockerfile" || lower == "makefile") return SHELL
            val ext = lower.substringAfterLast('.', "")
            return entries.firstOrNull { ext in it.extensions } ?: PLAIN
        }
    }
}

enum class TokenType { KEYWORD, TYPE, STRING, NUMBER, COMMENT, ANNOTATION, TAG, ATTRIBUTE, PUNCTUATION, HEADING, PLAIN }

data class Token(val start: Int, val end: Int, val type: TokenType)

/**
 * Regex lexer for editor highlighting. It is a tokenizer, not a parser: it colours
 * comments, strings, numbers, keywords and markup reliably but does not resolve symbols.
 */
object SyntaxHighlighter {

    private class Rule(val type: TokenType, regex: String, flags: Int = 0) {
        val pattern: Pattern = Pattern.compile(regex, flags)
    }

    private class Spec(val rules: List<Rule>, val keywords: Set<String>, val typesCapitalized: Boolean = true)

    private val identifier: Pattern = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*")

    private const val NUM = "0[xX][0-9a-fA-F_]+[lLuU]*|\\d[\\d_]*(\\.\\d[\\d_]*)?([eE][+-]?\\d+)?[fFdDlLuU]*"
    private const val DQ = "\"(?:\\\\.|[^\"\\\\\\n])*\"?"
    private const val SQ = "'(?:\\\\.|[^'\\\\\\n])*'?"
    private const val BLOCK = "/\\*[\\s\\S]*?(?:\\*/|\\z)"

    private val cLike = setOf(
        "if", "else", "for", "while", "do", "return", "break", "continue", "switch", "case", "default",
        "try", "catch", "finally", "throw", "new", "this", "null", "true", "false", "class", "interface",
        "enum", "public", "private", "protected", "static", "final", "void", "import", "package", "extends",
        "implements", "abstract", "const", "struct", "typedef", "sizeof", "goto", "volatile", "inline",
    )

    private val specs: Map<Language, Spec> by lazy {
        mapOf(
            Language.KOTLIN to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK),
                    Rule(TokenType.STRING, "\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.:]*"), Rule(TokenType.NUMBER, NUM),
                ),
                setOf(
                    "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for",
                    "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "is", "as", "in",
                    "null", "true", "false", "this", "super", "private", "public", "protected", "internal", "override",
                    "open", "abstract", "sealed", "data", "enum", "companion", "suspend", "inline", "reified", "crossinline",
                    "noinline", "lateinit", "const", "typealias", "by", "init", "constructor", "where", "out", "vararg",
                    "operator", "infix", "tailrec", "annotation", "inner", "value", "expect", "actual",
                ),
            ),
            Language.JAVA to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK),
                    Rule(TokenType.STRING, "\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.]*"), Rule(TokenType.NUMBER, NUM),
                ),
                cLike + setOf("int", "long", "short", "byte", "char", "float", "double", "boolean", "synchronized", "transient", "native", "instanceof", "throws", "assert", "var", "record", "yield", "super"),
            ),
            Language.PYTHON to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "#[^\\n]*"),
                    Rule(TokenType.STRING, "[rbfuRBFU]{0,2}(\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|'''[\\s\\S]*?(?:'''|\\z))"),
                    Rule(TokenType.STRING, "[rbfuRBFU]{0,2}$DQ"), Rule(TokenType.STRING, "[rbfuRBFU]{0,2}$SQ"),
                    Rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.]*"), Rule(TokenType.NUMBER, NUM),
                ),
                setOf(
                    "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "with", "try",
                    "except", "finally", "raise", "pass", "break", "continue", "lambda", "yield", "global", "nonlocal",
                    "in", "is", "not", "and", "or", "None", "True", "False", "async", "await", "del", "assert", "match", "case", "self",
                ),
            ),
            Language.JAVASCRIPT to jsSpec(false),
            Language.TYPESCRIPT to jsSpec(true),
            Language.DART to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK),
                    Rule(TokenType.STRING, "'''[\\s\\S]*?(?:'''|\\z)"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.]*"), Rule(TokenType.NUMBER, NUM),
                ),
                cLike + setOf("var", "late", "required", "async", "await", "dynamic", "final", "factory", "get", "set", "mixin", "with", "is", "as", "in", "int", "double", "bool", "String", "super", "Future", "Stream", "yield"),
            ),
            Language.C to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK),
                    Rule(TokenType.ANNOTATION, "#\\s*[a-z]+[^\\n]*"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.NUMBER, NUM),
                ),
                cLike + setOf("int", "long", "short", "char", "float", "double", "unsigned", "signed", "union", "extern", "register", "auto"),
            ),
            Language.CPP to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK),
                    Rule(TokenType.ANNOTATION, "#\\s*[a-z]+[^\\n]*"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.NUMBER, NUM),
                ),
                cLike + setOf("int", "long", "short", "char", "float", "double", "bool", "unsigned", "signed", "namespace", "using", "template", "typename", "virtual", "override", "nullptr", "auto", "constexpr", "noexcept", "delete", "operator", "friend", "mutable", "explicit", "std"),
            ),
            Language.SHELL to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "#[^\\n]*"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, "'[^']*'?"),
                    Rule(TokenType.ANNOTATION, "\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?|\\$[0-9@#?*$!-]"), Rule(TokenType.NUMBER, "\\b\\d+\\b"),
                ),
                setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function", "return", "export", "local", "readonly", "echo", "cd", "exit", "set", "unset", "source", "shift"),
                typesCapitalized = false,
            ),
            Language.SQL to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "--[^\\n]*"), Rule(TokenType.COMMENT, BLOCK), Rule(TokenType.STRING, "'(?:''|[^'])*'?"),
                    Rule(TokenType.NUMBER, NUM),
                ),
                setOf("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop", "alter", "index", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "offset", "and", "or", "not", "null", "primary", "key", "foreign", "references", "as", "distinct", "union", "all", "case", "when", "then", "else", "end", "integer", "text", "real", "blob", "default", "unique", "exists", "in", "like", "is"),
                typesCapitalized = false,
            ),
            Language.GRADLE to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.NUMBER, NUM),
                ),
                setOf("plugins", "dependencies", "android", "implementation", "api", "testImplementation", "repositories", "apply", "def", "task", "buildscript", "allprojects", "true", "false", "id", "version"),
            ),
            Language.JSON to Spec(
                listOf(
                    Rule(TokenType.ATTRIBUTE, "$DQ(?=\\s*:)"), Rule(TokenType.STRING, DQ), Rule(TokenType.NUMBER, "-?(?:$NUM)"),
                ),
                setOf("true", "false", "null"), typesCapitalized = false,
            ),
            Language.YAML to Spec(
                listOf(
                    Rule(TokenType.COMMENT, "#[^\\n]*"), Rule(TokenType.ATTRIBUTE, "[A-Za-z_][\\w.\\-]*(?=\\s*:)"),
                    Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ), Rule(TokenType.NUMBER, NUM),
                ),
                setOf("true", "false", "null", "yes", "no", "on", "off"), typesCapitalized = false,
            ),
            Language.CSS to Spec(
                listOf(
                    Rule(TokenType.COMMENT, BLOCK), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
                    Rule(TokenType.ATTRIBUTE, "[a-z\\-]+(?=\\s*:)"), Rule(TokenType.ANNOTATION, "@[a-z\\-]+"),
                    Rule(TokenType.NUMBER, "#[0-9a-fA-F]{3,8}\\b|-?\\d+(\\.\\d+)?(px|em|rem|%|vh|vw|s|ms|deg)?"),
                    Rule(TokenType.TAG, "[.#][A-Za-z_][\\w\\-]*"),
                ),
                setOf("important", "inherit", "initial", "none", "auto", "block", "flex", "grid", "absolute", "relative", "fixed"),
                typesCapitalized = false,
            ),
            Language.HTML to markupSpec(),
            Language.XML to markupSpec(),
            Language.MARKDOWN to Spec(
                listOf(
                    Rule(TokenType.STRING, "```[\\s\\S]*?(?:```|\\z)"), Rule(TokenType.HEADING, "(?m)^#{1,6} [^\\n]*"),
                    Rule(TokenType.STRING, "`[^`\\n]*`"), Rule(TokenType.KEYWORD, "\\*\\*[^*\\n]+\\*\\*"),
                    Rule(TokenType.ATTRIBUTE, "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)"), Rule(TokenType.PUNCTUATION, "(?m)^\\s*([-*+]|\\d+\\.) "),
                ),
                emptySet(), typesCapitalized = false,
            ),
        )
    }

    private fun jsSpec(ts: Boolean) = Spec(
        listOf(
            Rule(TokenType.COMMENT, "//[^\\n]*"), Rule(TokenType.COMMENT, BLOCK),
            Rule(TokenType.STRING, "`(?:\\\\[\\s\\S]|[^`\\\\])*`?"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
            Rule(TokenType.ANNOTATION, "@[A-Za-z_][\\w.]*"), Rule(TokenType.NUMBER, NUM),
        ),
        setOf(
            "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "break", "continue", "switch",
            "case", "default", "try", "catch", "finally", "throw", "new", "this", "null", "undefined", "true", "false",
            "class", "extends", "super", "import", "export", "from", "as", "async", "await", "yield", "typeof",
            "instanceof", "in", "of", "delete", "void", "static", "get", "set",
        ) + if (ts) setOf("interface", "type", "enum", "implements", "private", "public", "protected", "readonly", "declare", "namespace", "abstract", "keyof", "any", "unknown", "never", "string", "number", "boolean") else emptySet(),
    )

    private fun markupSpec() = Spec(
        listOf(
            Rule(TokenType.COMMENT, "<!--[\\s\\S]*?(?:-->|\\z)"), Rule(TokenType.ANNOTATION, "<\\?[\\s\\S]*?(?:\\?>|\\z)|<!DOCTYPE[^>]*>"),
            Rule(TokenType.TAG, "</?[A-Za-z][\\w:.\\-]*"), Rule(TokenType.TAG, "/?>"),
            Rule(TokenType.ATTRIBUTE, "[A-Za-z_:][\\w:.\\-]*(?=\\s*=)"), Rule(TokenType.STRING, DQ), Rule(TokenType.STRING, SQ),
        ),
        emptySet(), typesCapitalized = false,
    )

    /** Files above this size are shown without highlighting to keep typing responsive. */
    const val MAX_HIGHLIGHT_CHARS = 200_000

    fun tokenize(text: String, language: Language): List<Token> {
        if (text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        val spec = specs[language] ?: return emptyList()
        val matchers: List<Pair<TokenType, Matcher>> = spec.rules.map {
            it.type to it.pattern.matcher(text).useAnchoringBounds(false).useTransparentBounds(true)
        }
        val skipWhitespace = language != Language.MARKDOWN
        val idMatcher = identifier.matcher(text)
        val caseInsensitiveKeywords = language == Language.SQL
        val tokens = ArrayList<Token>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (skipWhitespace && text[i].isWhitespace()) {
                i++
                continue
            }
            var matched = false
            for ((type, m) in matchers) {
                m.region(i, n)
                if (m.lookingAt() && m.end() > i) {
                    tokens.add(Token(i, m.end(), type))
                    i = m.end()
                    matched = true
                    break
                }
            }
            if (matched) continue
            val c = text[i]
            if (c.isLetter() || c == '_' || c == '$') {
                idMatcher.region(i, n)
                if (idMatcher.lookingAt()) {
                    val word = text.substring(i, idMatcher.end())
                    val key = if (caseInsensitiveKeywords) word.lowercase() else word
                    when {
                        key in spec.keywords -> tokens.add(Token(i, idMatcher.end(), TokenType.KEYWORD))
                        spec.typesCapitalized && word[0].isUpperCase() -> tokens.add(Token(i, idMatcher.end(), TokenType.TYPE))
                    }
                    i = idMatcher.end()
                    continue
                }
            }
            if (c in "{}()[];,.<>:=+-*/%!&|^~?") tokens.add(Token(i, i + 1, TokenType.PUNCTUATION))
            i++
        }
        return tokens
    }

    /** Index of the bracket matching the one at [index], or -1. Ignores brackets inside strings/comments of [tokens]. */
    fun matchingBracket(text: String, index: Int, tokens: List<Token>): Int {
        if (index < 0 || index >= text.length) return -1
        val open = "([{"
        val close = ")]}"
        val c = text[index]
        val forward = open.indexOf(c) >= 0
        val pairIdx = if (forward) open.indexOf(c) else close.indexOf(c)
        if (pairIdx < 0) return -1
        val target = if (forward) close[pairIdx] else open[pairIdx]
        val skipRanges = tokens.filter { it.type == TokenType.STRING || it.type == TokenType.COMMENT }
        fun skipped(pos: Int) = skipRanges.any { pos >= it.start && pos < it.end }
        var depth = 0
        var i = index
        val limit = 200_000
        var steps = 0
        while (i in text.indices && steps < limit) {
            val ch = text[i]
            if (!skipped(i)) {
                if (ch == c) depth++ else if (ch == target) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i += if (forward) 1 else -1
            steps++
        }
        return -1
    }
}
