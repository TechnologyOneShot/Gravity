package com.gravity.ide.core

import org.json.JSONObject

/**
 * Tool calling for local models that have no native function-calling API.
 * The model is instructed to emit blocks like:
 *
 * ```tool
 * {"name": "read_file", "arguments": {"path": "app/build.gradle.kts"}}
 * ```
 *
 * Parsed calls are executed by the same tool layer the cloud providers use.
 */
object TextToolProtocol {

    data class ParsedCall(val name: String, val argumentsJson: String)

    private val fenced = Regex("```tool\\s*\\n([\\s\\S]*?)```")

    fun instructions(toolSchemas: List<Pair<String, String>>): String = buildString {
        appendLine("You can call tools. To call a tool, reply with one or more blocks exactly like:")
        appendLine("```tool")
        appendLine("{\"name\": \"<tool name>\", \"arguments\": { ... }}")
        appendLine("```")
        appendLine("Wait for the tool results before continuing. When the task is complete, call the tool \"finish\".")
        appendLine("Available tools:")
        for ((name, description) in toolSchemas) appendLine("- $name: $description")
    }

    fun parse(text: String): List<ParsedCall> {
        val out = mutableListOf<ParsedCall>()
        for (m in fenced.findAll(text)) {
            val body = m.groupValues[1].trim()
            try {
                val obj = JSONObject(body)
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val args = obj.optJSONObject("arguments") ?: JSONObject()
                out += ParsedCall(name, args.toString())
            } catch (_: Exception) {
                // Malformed block: ignored; the agent reports "no valid tool call" to the model.
            }
        }
        return out
    }

    /** Text with tool blocks removed (what the user sees as the model's prose). */
    fun stripCalls(text: String): String = fenced.replace(text, "").trim()
}
