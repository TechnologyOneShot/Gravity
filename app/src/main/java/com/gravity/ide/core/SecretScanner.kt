package com.gravity.ide.core

/**
 * Detects likely credentials before content leaves the device (cloud AI, web search,
 * exports). Matches are redacted, never logged in clear text.
 */
object SecretScanner {

    data class Finding(val kind: String, val start: Int, val end: Int)

    private val patterns = listOf(
        "Google API key" to Regex("""AIza[0-9A-Za-z_\-]{35}"""),
        "OpenAI-style key" to Regex("""\bsk-(proj-|ant-)?[A-Za-z0-9_\-]{20,}"""),
        "GitHub token" to Regex("""\bgh[pousr]_[A-Za-z0-9]{30,}"""),
        "AWS access key" to Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b"""),
        "Slack token" to Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}"""),
        "Private key block" to Regex("""-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----[\s\S]*?-----END (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"""),
        "Keystore password" to Regex("""(?i)(storePassword|keyPassword)\s*[=:]\s*["']?[^\s"']{4,}"""),
        "Generic secret assignment" to Regex("""(?i)\b(api[_-]?key|secret|password|token)\b\s*[=:]\s*["'][^"'\s]{12,}["']"""),
    )

    /** Files that should never be sent to a cloud model, regardless of content. */
    private val sensitiveFileNames = listOf(
        Regex("""(^|/)\.env(\..+)?$"""),
        Regex("""(^|/)local\.properties$"""),
        Regex("""\.(jks|keystore|p12|pem|key)$"""),
        Regex("""(^|/)google-services\.json$"""),
        Regex("""(^|/)id_(rsa|ed25519|ecdsa)$"""),
    )

    fun scan(text: String): List<Finding> {
        val out = mutableListOf<Finding>()
        for ((kind, regex) in patterns) {
            regex.findAll(text).forEach { out += Finding(kind, it.range.first, it.range.last + 1) }
        }
        return out.sortedBy { it.start }
    }

    fun redact(text: String): Pair<String, List<Finding>> {
        val findings = scan(text)
        if (findings.isEmpty()) return text to findings
        val sb = StringBuilder()
        var cursor = 0
        for (f in findings) {
            if (f.start < cursor) continue
            sb.append(text, cursor, f.start)
            sb.append("[REDACTED ").append(f.kind).append(']')
            cursor = f.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString() to findings
    }

    fun isSensitiveFile(relativePath: String): Boolean {
        val p = relativePath.replace('\\', '/')
        return sensitiveFileNames.any { it.containsMatchIn(p) }
    }
}
