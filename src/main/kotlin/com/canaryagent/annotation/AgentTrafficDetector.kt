package com.canaryagent.annotation

import com.canaryagent.util.JsonSupport
import org.json.JSONArray
import org.json.JSONObject

/** How much a [Signal] proves: one STRONG signal makes a message agent traffic; MEDIUM is suggestive only. */
enum class Strength { STRONG, MEDIUM }

/** Individual heuristics from docs/Agent-Project-Sketch-Revised.md, "Passive annotation rules". */
enum class Signal(val strength: Strength, val label: String) {
    TOOL_KEYS(Strength.STRONG, "tool keys"),
    TOOL_USE_RESULT(Strength.STRONG, "tool use/result"),
    MESSAGES_ARRAY(Strength.STRONG, "messages array"),
    /** A role array under any other key (`chat_history`, `history`, top level) with an LLM-specific role. */
    CONVERSATION_ARRAY(Strength.STRONG, "conversation array"),
    TOOL_CALL_ID(Strength.STRONG, "tool_call_id"),
    TOOL_SCHEMA(Strength.STRONG, "tool schema"),
    FUNCTION_OBJECT(Strength.STRONG, "function object"),
    ROLE_ARRAY(Strength.MEDIUM, "role messages"),
    SCHEMA_KEYS(Strength.MEDIUM, "schema keys"),
    TOOL_RESULT_SHAPE(Strength.MEDIUM, "tool result shape"),
    FRAMEWORK_KEYS(Strength.MEDIUM, "framework keys"),
    AGENT_PATH(Strength.MEDIUM, "agent path"),
}

/** Outcome of running the heuristics over one message. */
data class Detection(val signals: Set<Signal>) {
    val strong: Boolean get() = signals.any { it.strength == Strength.STRONG }
    val medium: Boolean get() = signals.any { it.strength == Strength.MEDIUM }
    val isAgentRelated: Boolean get() = signals.isNotEmpty()

    /** Union of both signal sets; an [other] with no signals leaves this instance as it is. */
    fun merge(other: Detection): Detection = if (other.signals.isEmpty()) this else Detection(signals + other.signals)

    companion object {
        val NONE = Detection(emptySet())
    }
}

/**
 * Applies the strong and medium agent-traffic heuristics to parsed JSON roots.
 * Key matching is case-insensitive and structural (never regex over raw text). Biased toward
 * false positives over false negatives, but generic keys (`name`, `content`, `message`, `data`)
 * never fire on their own.
 */
class AgentTrafficDetector(private val maxDepth: Int = JsonSupport.DEFAULT_MAX_DEPTH) {

    companion object {
        val TOOL_LIST_KEYS = setOf("tools", "functions", "available_tools", "tool_definitions", "actions")
        private val TOOL_KEYS = setOf("tool_calls", "tool_choice", "function_call", "parallel_tool_calls")
        private val TOOL_USE_RESULT_KEYS = setOf("tool_use", "tool_result", "function_result")
        private val TOOL_USE_RESULT_TYPES = setOf("tool_use", "tool_result", "function_call", "function_call_output", "tool_call")
        private val ROLES = setOf("system", "assistant", "user", "tool", "function", "developer")

        /** Roles that only an LLM conversation carries; `user` alone also fits ordinary chat or ticket apps. */
        private val LLM_ROLES = ROLES - "user"
        private val SCHEMA_KEYS = setOf("parameters", "input_schema", "properties", "required")
        private val SCHEMA_MARKERS = setOf("type", "properties", "required")
        private val FRAMEWORK_KEYS = setOf("tool_input", "action_input", "tool_name")
        private val RESULT_KEYS = setOf("result", "output", "content")
        private val PATH_FRAGMENTS = listOf("/chat/completions", "/v1/messages", "/agents", "/tools", "/functions", "/invoke")
        private val WEAK_TOP_LEVEL = setOf("model", "prompt", "input", "messages", "stream", "max_tokens", "temperature", "system", "instructions")
        private const val SAMPLE = 20
    }

    /**
     * Runs every heuristic over [roots] (parsed JSON values of one message) and [path] (the request
     * path, for the agent-path signal). [Detection.NONE] when there is nothing to inspect.
     */
    fun detect(roots: List<Any>, path: String? = null): Detection {
        if (roots.isEmpty()) return Detection.NONE
        val signals = HashSet<Signal>()
        for (root in roots) {
            JsonSupport.walk(root, maxDepth) { keys, node ->
                when (node) {
                    is JSONObject -> inspectObject(node, keys, signals)
                    is JSONArray -> inspectArray(node, keys, signals)
                }
            }
        }
        if (path != null && PATH_FRAGMENTS.any { path.lowercase().contains(it) }) {
            val weak = roots.any { r -> r is JSONObject && r.keySet().any { it.lowercase() in WEAK_TOP_LEVEL } }
            if (signals.isNotEmpty() || weak) signals += Signal.AGENT_PATH
        }
        return Detection(signals)
    }

    private fun inspectObject(obj: JSONObject, keys: List<String>, out: MutableSet<Signal>) {
        val lower = HashMap<String, String>(obj.length() * 2)
        for (k in obj.keySet()) lower[k.lowercase()] = k
        fun has(k: String) = lower.containsKey(k)
        fun get(k: String): Any? = lower[k]?.let { obj.opt(it) }

        for (k in lower.keys) {
            when {
                k in TOOL_LIST_KEYS && get(k) is JSONArray -> out += Signal.TOOL_KEYS
                k in TOOL_KEYS -> out += Signal.TOOL_KEYS
                k in TOOL_USE_RESULT_KEYS -> out += Signal.TOOL_USE_RESULT
                k in FRAMEWORK_KEYS -> out += Signal.FRAMEWORK_KEYS
            }
        }
        (get("type") as? String)?.let { if (it.lowercase() in TOOL_USE_RESULT_TYPES) out += Signal.TOOL_USE_RESULT }

        (get("messages") as? JSONArray)?.let { if (isMessagesArray(it)) out += Signal.MESSAGES_ARRAY }

        if (has("tool_call_id") && RESULT_KEYS.any { has(it) }) out += Signal.TOOL_CALL_ID

        (get("function") as? JSONObject)?.let { fn ->
            val fk = fn.keySet().map { it.lowercase() }.toSet()
            if ("name" in fk && ("arguments" in fk || "parameters" in fk)) out += Signal.FUNCTION_OBJECT
        }

        val name = get("name")
        if (name is String) {
            val inToolList = keys.any { it.lowercase() in TOOL_LIST_KEYS }
            val schema = (get("parameters") ?: get("input_schema")) as? JSONObject
            if (schema != null && (inToolList || looksLikeSchema(schema))) {
                out += if (inToolList) Signal.TOOL_SCHEMA else Signal.SCHEMA_KEYS
            } else if (SCHEMA_KEYS.any { has(it) } && (has("properties") || has("required"))) {
                out += Signal.SCHEMA_KEYS
            }
            if (RESULT_KEYS.any { has(it) } && keys.any { it.lowercase().let { a -> a.contains("tool") || a.contains("function") } }) {
                out += Signal.TOOL_RESULT_SHAPE
            }
        }
    }

    /**
     * A role array outside `messages` (F5): strong when an entry carries an LLM-specific role, whatever the
     * key is called (`chat_history`, `history`, `conversation`, a top-level array); medium for `user` only.
     */
    private fun inspectArray(arr: JSONArray, keys: List<String>, out: MutableSet<Signal>) {
        if (keys.lastOrNull()?.lowercase() == "messages") return // already a strong signal via the parent
        val roles = messageRoles(arr)
        when {
            roles.any { it in LLM_ROLES } -> out += Signal.CONVERSATION_ARRAY
            roles.isNotEmpty() -> out += Signal.ROLE_ARRAY
        }
    }

    /** Array with at least one object carrying a known `role` and a `content`/`text` field. */
    private fun isMessagesArray(arr: JSONArray): Boolean = messageRoles(arr).isNotEmpty()

    /** Known roles of the sampled entries that have a `content` or `text` field. */
    private fun messageRoles(arr: JSONArray): Set<String> {
        val out = HashSet<String>()
        val n = minOf(arr.length(), SAMPLE)
        for (i in 0 until n) {
            val o = arr.opt(i) as? JSONObject ?: continue
            val lk = o.keySet().associateBy { it.lowercase() }
            val role = (lk["role"]?.let { o.opt(it) } as? String)?.lowercase() ?: continue
            if (role in ROLES && ("content" in lk || "text" in lk)) out += role
        }
        return out
    }

    private fun looksLikeSchema(o: JSONObject): Boolean = o.keySet().any { it.lowercase() in SCHEMA_MARKERS }
}
