package com.canaryagent.inventory

import com.canaryagent.util.JsonSupport
import org.json.JSONArray
import org.json.JSONObject

/** A tool name with the parameter or argument keys visible in one message. */
data class ObservedTool(val name: String, val parameterKeys: Set<String>)

/**
 * Pulls tool names and visible parameter/argument keys out of parsed bodies: tool definitions
 * (`tools[]`, `functions[]`, `input_schema`), tool calls (`tool_calls[].function`, `function_call`,
 * `tool_use` blocks) and common framework shapes (`tool` + `tool_input`). No deep schema rendering.
 */
object ToolExtractor {
    private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.:/-]{0,63}$")

    /** Framework-style name keys are specific enough on their own. */
    private val FRAMEWORK_NAME_KEYS = listOf("tool_name", "tool", "action")
    private val FRAMEWORK_ARG_KEYS = listOf("tool_input", "action_input", "args", "arguments", "input")

    /** Plain `name` needs a schema-like value or a tool-ish ancestor to count. */
    private val NAME_ARG_KEYS = listOf("input_schema", "parameters", "arguments", "input")
    private val SCHEMA_MARKERS = setOf("type", "properties", "required")

    /** Tools in [roots], deduplicated by lowercased name (first-seen casing kept, keys merged). */
    fun extract(roots: List<Any>, maxDepth: Int = JsonSupport.DEFAULT_MAX_DEPTH): List<ObservedTool> {
        val found = LinkedHashMap<String, ObservedTool>()
        fun add(name: String, keys: Set<String>) {
            val k = name.lowercase()
            val prev = found[k]
            found[k] = if (prev == null) ObservedTool(name, keys) else prev.copy(parameterKeys = prev.parameterKeys + keys)
        }
        for (root in roots) {
            JsonSupport.walk(root, maxDepth) { keys, node ->
                if (node is JSONObject) toolIn(node, isToolContext(keys))?.let { add(it.name, it.parameterKeys) }
            }
        }
        return found.values.toList()
    }

    /** True when some ancestor key is tool-related (`tools`, `tool_calls`, `function_call`, `actions`, ...). */
    private fun isToolContext(keys: List<String>): Boolean =
        keys.any { k -> k.lowercase().let { it.contains("tool") || it.contains("function") || it == "actions" } }

    private fun toolIn(obj: JSONObject, toolContext: Boolean): ObservedTool? {
        val lk = obj.keySet().associateBy { it.lowercase() }
        fun get(k: String): Any? = lk[k]?.let { obj.opt(it) }

        // OpenAI style: { "function": { "name": ..., "arguments" | "parameters": ... } }
        (get("function") as? JSONObject)?.let { fn -> toolIn(fn, true)?.let { return it } }

        FRAMEWORK_NAME_KEYS.firstNotNullOfOrNull { get(it) as? String }?.takeIf { NAME.matches(it) }?.let { name ->
            for (argKey in FRAMEWORK_ARG_KEYS) {
                val keys = get(argKey)?.let(::argumentKeys) ?: continue
                return ObservedTool(name, keys)
            }
        }

        val name = (get("name") as? String)?.takeIf { NAME.matches(it) } ?: return null
        val isToolUse = (get("type") as? String)?.lowercase() == "tool_use"
        for (argKey in NAME_ARG_KEYS) {
            val raw = get(argKey) ?: continue
            val specific = when (argKey) {
                "input_schema" -> true
                "parameters" -> toolContext || (raw is JSONObject && looksLikeSchema(raw))
                else -> toolContext || isToolUse
            }
            if (!specific) continue
            val keys = argumentKeys(raw) ?: continue
            return ObservedTool(name, keys)
        }
        return null
    }

    private fun looksLikeSchema(o: JSONObject): Boolean = o.keySet().any { it.lowercase() in SCHEMA_MARKERS }

    /**
     * Visible keys of an arguments/schema value; JSON strings are parsed one level. Null if not an object.
     * Keys are sorted alphabetically: org.json does not preserve source order, so this is the stable choice.
     */
    private fun argumentKeys(value: Any): Set<String>? {
        val obj: JSONObject = when (value) {
            is JSONObject -> value
            is String -> JsonSupport.parseLenient(value) as? JSONObject ?: return if (value.isBlank()) emptySet() else null
            is JSONArray -> return emptySet()
            else -> return null
        }
        val props = obj.keySet().firstOrNull { it.equals("properties", ignoreCase = true) }?.let { obj.opt(it) as? JSONObject }
        val source = props ?: obj
        val keys = source.keySet()
        return (if (props == null) keys.filterNot { it.lowercase() in SCHEMA_META } else keys).toSortedSet()
    }

    private val SCHEMA_META = setOf("type", "required", "additionalproperties", "description", "\$schema", "title")
}
