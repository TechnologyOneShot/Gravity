package com.gravity.ide.core

/**
 * Classifies shell commands before the agent (or a user via AI suggestion) runs them.
 * Anything not SAFE requires explicit approval in the UI.
 */
object CommandSafety {

    enum class Risk { SAFE, CAUTION, DANGEROUS }

    data class Assessment(val risk: Risk, val reasons: List<String>)

    private data class Rule(val regex: Regex, val risk: Risk, val reason: String)

    private val rules = listOf(
        Rule(Regex("""\brm\s+(-[a-zA-Z]*[rRf][a-zA-Z]*\s+)+"""), Risk.DANGEROUS, "Recursive or forced delete (rm -r/-f)"),
        Rule(Regex("""\brm\s"""), Risk.CAUTION, "Deletes files"),
        Rule(Regex("""\b(mkfs|fdisk|parted|wipefs)\b"""), Risk.DANGEROUS, "Formats or partitions storage"),
        Rule(Regex("""\bdd\s+.*\bof="""), Risk.DANGEROUS, "Raw write with dd"),
        Rule(Regex(""">\s*/dev/(block|sd|mmc|nvme)"""), Risk.DANGEROUS, "Writes to a block device"),
        Rule(Regex("""\b(reboot|shutdown|poweroff|halt)\b"""), Risk.DANGEROUS, "Restarts or powers off the device"),
        Rule(Regex("""\bsu\b|\bsudo\b"""), Risk.DANGEROUS, "Privilege escalation"),
        Rule(Regex("""\bmount\b|\bumount\b"""), Risk.DANGEROUS, "Changes mounts"),
        Rule(Regex("""\bchmod\s+(-R\s+)?[0-7]*7[0-7]*7\b"""), Risk.CAUTION, "Makes files world-writable"),
        Rule(Regex("""\bchmod\s+-R\b|\bchown\s+-R\b"""), Risk.CAUTION, "Recursive permission change"),
        Rule(Regex("""\bkill\s+-9\s+-1\b|\bkillall\b|\bpkill\b"""), Risk.DANGEROUS, "Kills many processes"),
        Rule(Regex("""(curl|wget)[^|;&]*\|\s*(ba|z|k)?sh\b"""), Risk.DANGEROUS, "Pipes a download straight into a shell"),
        Rule(Regex("""\b(curl|wget)\b"""), Risk.CAUTION, "Network access"),
        Rule(Regex("""\bpm\s+(uninstall|clear|disable|grant|revoke|reset-permissions)\b"""), Risk.DANGEROUS, "Changes installed packages or permissions"),
        Rule(Regex("""\bpm\s+install\b"""), Risk.CAUTION, "Installs a package"),
        Rule(Regex("""\b(settings\s+put|setprop|svc)\b"""), Risk.DANGEROUS, "Changes system settings"),
        Rule(Regex("""\bam\s+(force-stop|kill)\b"""), Risk.CAUTION, "Stops an app"),
        Rule(Regex("""\bgit\s+push\b.*(--force|-f\b)"""), Risk.DANGEROUS, "Force push rewrites remote history"),
        Rule(Regex("""\bgit\s+(reset\s+--hard|clean\s+-[a-zA-Z]*f)"""), Risk.DANGEROUS, "Discards local changes"),
        Rule(Regex("""\bfind\b.*\s-delete\b"""), Risk.DANGEROUS, "find -delete removes files"),
        Rule(Regex(""":\(\)\s*\{\s*:\|:&\s*\};:"""), Risk.DANGEROUS, "Fork bomb"),
        Rule(Regex("""\btruncate\b|>\s*[^\s|&;]+\s*$"""), Risk.CAUTION, "Overwrites a file"),
        Rule(Regex("""\bmv\b"""), Risk.CAUTION, "Moves or renames files"),
        Rule(Regex("""(^|\s)/(system|vendor|data/data|data/app)\b"""), Risk.CAUTION, "Touches system or other apps' paths"),
    )

    fun assess(command: String): Assessment {
        val reasons = mutableListOf<String>()
        var risk = Risk.SAFE
        for (rule in rules) {
            if (rule.regex.containsMatchIn(command)) {
                reasons += rule.reason
                if (rule.risk.ordinal > risk.ordinal) risk = rule.risk
            }
        }
        return Assessment(risk, reasons.distinct())
    }
}
