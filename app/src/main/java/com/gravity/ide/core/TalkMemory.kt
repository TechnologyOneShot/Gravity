package com.gravity.ide.core

/**
 * What Yamini remembers about the user from live talks, like a friend would: their name, where they
 * live, what they like or do not like, what they study or do. Taken only from plain sentences the
 * user says about themselves ("mera naam Ravi hai", "mujhe cricket pasand hai", "I live in Pune"),
 * kept on the phone, shown in Settings → Voice where the user can delete it. Pure Kotlin, tested.
 */
object TalkMemory {
    enum class Kind(val label: String, val single: Boolean) {
        NAME("Name", true), PLACE("Lives in", true), LIKES("Likes", false), DISLIKES("Does not like", false), DOES("Studies / works", false),
    }

    data class Fact(val kind: Kind, val value: String)

    private const val W = "[\\p{L}\\p{M}]+"
    private const val PHRASE = "([\\p{L}\\p{M}\\p{N} '-]{2,40}?)"
    private const val END = "(?=\\s*(?:[.,!?।]|$|\\s+(?:aur|and|par|but|lekin|kyunki|because|और|पर|लेकिन)\\s))"

    private val patterns: List<Pair<Kind, Regex>> = listOf(
        Kind.NAME to Regex("(?i)(?:mera|meraa)\\s+naam\\s+($W)"),
        Kind.NAME to Regex("(?i)my\\s+name\\s+is\\s+($W)"),
        Kind.NAME to Regex("मेरा\\s+नाम\\s+($W)"),
        Kind.PLACE to Regex("(?i)(?:main|mai|mein|me)\\s+($W)\\s+(?:me|mein|men)\\s+(?:rehta|rehti|rahta|rahti)"),
        Kind.PLACE to Regex("(?i)i\\s+(?:live|stay)\\s+in\\s+($W)"),
        Kind.PLACE to Regex("(?i)i(?:'m|\\s+am)\\s+from\\s+($W)"),
        Kind.PLACE to Regex("मैं\\s+($W)\\s+में\\s+(?:रहता|रहती)"),
        Kind.DISLIKES to Regex("(?i)mujhe\\s+$PHRASE\\s+(?:bilkul\\s+)?(?:pasand|achha|achchha|accha)\\s+(?:nahi|nahin|nhi)"),
        Kind.DISLIKES to Regex("(?i)i\\s+(?:don'?t|do\\s+not)\\s+like\\s+$PHRASE$END"),
        Kind.DISLIKES to Regex("(?i)i\\s+hate\\s+$PHRASE$END"),
        Kind.DISLIKES to Regex("मुझे\\s+$PHRASE\\s+पसंद\\s+नहीं"),
        Kind.LIKES to Regex("(?i)mujhe\\s+$PHRASE\\s+(?:bahut\\s+|bohot\\s+|bahot\\s+)?(?:pasand\\s+(?:hai|he|h)|achha\\s+lagta|achchha\\s+lagta|accha\\s+lagta)(?!\\s+(?:nahi|nahin|nhi))"),
        Kind.LIKES to Regex("(?i)(?<!don't\\s|not\\s)i\\s+(?:really\\s+)?(?:like|love|enjoy)\\s+$PHRASE$END"),
        Kind.LIKES to Regex("मुझे\\s+$PHRASE\\s+(?:बहुत\\s+)?पसंद\\s+है"),
        Kind.DOES to Regex("(?i)(?:main|mai)\\s+$PHRASE\\s+(?:padhta|padhti|padh\\s+raha|padh\\s+rahi)\\s+(?:hun|hoon|hu|hu)"),
        Kind.DOES to Regex("(?i)(?:main|mai)\\s+$PHRASE\\s+(?:ki|ka)\\s+(?:taiyari|tayari|preparation)"),
        Kind.DOES to Regex("(?i)i\\s+(?:study|am\\s+studying)\\s+$PHRASE$END"),
        Kind.DOES to Regex("(?i)i\\s+work\\s+(?:as|at|in)\\s+(?:a\\s+|an\\s+)?$PHRASE$END"),
        Kind.DOES to Regex("मैं\\s+$PHRASE\\s+(?:पढ़ता|पढ़ती)\\s+हूँ"),
    )

    // Words that cannot be a remembered value (questions, "this/that", filler).
    private val notAValue = Regex(
        "(?i)^(kya|kaun|kaise|kahan|kab|kyun|ye|yeh|wo|woh|vo|is|us|isko|usko|this|that|it|what|who|where|how|why|kuch|sab|nahi|hai|tum|aap|you|" +
            "क्या|कौन|कैसे|कहाँ|ये|यह|वो|वह|इसे|उसे|कुछ|सब|तुम|आप)(\\s|$)",
    )

    /** The facts in what the user just said about themselves. */
    fun extract(said: String): List<Fact> {
        if (said.contains('?')) return emptyList()
        val out = ArrayList<Fact>()
        val taken = HashSet<IntRange>()
        for ((kind, re) in patterns) {
            for (m in re.findAll(said)) {
                if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
                val v = m.groupValues[1].trim().trim('\'', '-').replace(Regex("\\s{2,}"), " ")
                if (v.length < 2 || notAValue.containsMatchIn(v)) continue
                taken += m.range
                out += Fact(kind, if (kind == Kind.NAME || kind == Kind.PLACE) v.replaceFirstChar { it.uppercase() } else v.lowercase())
            }
        }
        return out
    }

    /** [old] with [new]: a new name or place replaces the old one; likes and others are added (newest kept). */
    fun merge(old: List<Fact>, new: List<Fact>, maxEach: Int = 8): List<Fact> {
        var all = old
        for (f in new) {
            all = if (f.kind.single) all.filter { it.kind != f.kind } + f
            else all.filter { !(it.kind == f.kind && it.value.equals(f.value, true)) } + f
            // Liking and not liking the same thing: the newest wins.
            val opposite = when (f.kind) { Kind.LIKES -> Kind.DISLIKES; Kind.DISLIKES -> Kind.LIKES; else -> null }
            if (opposite != null) all = all.filter { !(it.kind == opposite && it.value.equals(f.value, true)) }
        }
        return Kind.entries.flatMap { k -> all.filter { it.kind == k }.takeLast(if (k.single) 1 else maxEach) }
    }

    /** The memory as one instruction line for the AI, or "" when there is none. */
    fun forPrompt(facts: List<Fact>): String {
        if (facts.isEmpty()) return ""
        val parts = Kind.entries.mapNotNull { k ->
            facts.filter { it.kind == k }.map { it.value }.takeIf { it.isNotEmpty() }?.let { "${k.label.lowercase()}: ${it.joinToString(", ")}" }
        }
        return " What you remember about the user from earlier talks (use it naturally when it fits, never list it): ${parts.joinToString("; ")}."
    }

    fun encode(facts: List<Fact>): String = facts.joinToString("\n") { "${it.kind.name}\t${it.value.replace('\n', ' ').replace('\t', ' ')}" }

    fun decode(s: String?): List<Fact> = s.orEmpty().lines().mapNotNull { l ->
        val tab = l.indexOf('\t')
        if (tab <= 0) null else runCatching { Fact(Kind.valueOf(l.substring(0, tab)), l.substring(tab + 1)) }.getOrNull()
    }
}
