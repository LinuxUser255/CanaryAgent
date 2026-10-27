package com.canaryagent.markers

import com.canaryagent.model.MarkerEncoding
import com.canaryagent.util.JsonSupport
import org.json.JSONObject
import java.util.EnumSet

/** One tracked token observed in a message, with every encoding it was seen in. */
data class MarkerMatch(
    val token: String,
    val encodings: Set<MarkerEncoding>,
    /** True if the token sat inside a value under a tool-call key such as `tool_calls` or `arguments`. */
    val inToolCall: Boolean,
    /**
     * True if the token sat inside a tool *result* handed to the model (`role: tool` message,
     * `tool_result` block, `function_call_output`, `functionResponse`): content the agent read and
     * delivered, as opposed to text the user typed.
     */
    val inToolResult: Boolean = false,
)

/**
 * Encoding-tolerant marker detection over the text of one message.
 *
 * Does a single case-insensitive search for the marker prefix in the raw text and in each decoded
 * variant (JSON-unescaped, percent-decoded, base64 runs), then looks candidates up in the
 * [MarkerIndex]. Tracked markers are never iterated per message, except the (bounded) set of
 * custom tokens that have no shared prefix. Pure Kotlin, no Montoya types, so it is unit-testable.
 */
class MarkerScanner(
    private val index: MarkerIndex,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    private val maxJsonDepth: Int = JsonSupport.DEFAULT_MAX_DEPTH,
) {
    companion object {
        const val DEFAULT_MAX_CHARS = 2 * 1024 * 1024

        /** A token under any of these keys (or a descendant) counts as "seen in tool call". */
        val TOOL_CALL_KEYS: Set<String> = setOf("tool_calls", "function_call", "tool_use", "arguments", "input")

        /** An object with one of these `role` values is a tool result message (OpenAI `tool`, legacy `function`). */
        val TOOL_RESULT_ROLES: Set<String> = setOf("tool", "function")

        /** An object with one of these `type` values is a tool result block (Anthropic, OpenAI Responses). */
        val TOOL_RESULT_TYPES: Set<String> = setOf("tool_result", "function_call_output", "function_result")

        /** The value under one of these keys is a tool result (Gemini-style parts, generic wrappers). */
        val TOOL_RESULT_KEYS: Set<String> = setOf("functionresponse", "function_response", "tool_result", "function_result")

        private val BASE64_RUN = Regex("[A-Za-z0-9+/_-]{16,}={0,2}")
        private const val MAX_BASE64_RUNS = 500
        private const val MAX_CUSTOM_TOKENS = 64
    }

    /** Occurrences of one token in one piece of text. */
    private class Found(var total: Int = 0, var exact: Int = 0)

    /**
     * Scans [text] for markers in every encoding. Tool-call classification parses [structuredBody]
     * when given (the HTTP body), otherwise [text] itself (a WebSocket payload). Pass the body
     * separately for HTTP messages: a start line and headers in front of the JSON defeat the parser.
     * Input beyond [maxChars] is ignored.
     */
    fun scan(text: String, structuredBody: String? = null): List<MarkerMatch> {
        if (text.isEmpty()) return emptyList()
        val capped = if (text.length > maxChars) text.substring(0, maxChars) else text
        return scan(
            text,
            lazy {
                val body = structuredBody?.let { if (it.length > maxChars) it.substring(0, maxChars) else it } ?: capped
                JsonSupport.parseFragments(body)
            },
        )
    }

    /**
     * Variant for callers that share one parse between scanning and annotation (F-review [2]):
     * [fragments] holds the parsed roots of the structured body and is only forced when the text
     * actually contains a tracked token, so a message without markers costs no parse here.
     */
    fun scan(text: String, fragments: Lazy<List<Any>>): List<MarkerMatch> {
        if (text.isEmpty()) return emptyList()
        if (index.prefixedTokenLengths().isEmpty() && index.customTokens().isEmpty()) return emptyList()
        val capped = if (text.length > maxChars) text.substring(0, maxChars) else text

        val found = tokensIn(capped)
        if (found.isEmpty()) return emptyList()

        val roots = fragments.value
        val inToolCall = classifyToolCall(roots, found.keys)
        val inToolResult = classifyToolResult(roots, found.keys)
        return found.map { (token, enc) -> MarkerMatch(token, enc, token in inToolCall, token in inToolResult) }
    }

    /**
     * All tracked tokens present in [text] in any supported encoding, with the encodings that
     * actually contributed an occurrence. A decoded variant only counts when it yields more
     * occurrences than the raw text (an alphanumeric token percent-decodes to itself).
     * The result is an `EnumSet`, so labels iterate in declaration order.
     */
    internal fun tokensIn(text: String): Map<String, Set<MarkerEncoding>> {
        val found = LinkedHashMap<String, EnumSet<MarkerEncoding>>()

        fun add(token: String, enc: MarkerEncoding) {
            found.getOrPut(token) { EnumSet.noneOf(MarkerEncoding::class.java) }.add(enc)
        }

        // 1. Raw text, exact and re-cased occurrences.
        val raw = findRaw(text)
        for ((token, f) in raw) {
            if (f.exact > 0) add(token, MarkerEncoding.RAW)
            if (f.total > f.exact) add(token, MarkerEncoding.CASE_CHANGED)
        }

        fun scanDecoded(variant: String, enc: MarkerEncoding) {
            for ((token, f) in findRaw(variant)) if (f.total > (raw[token]?.total ?: 0)) add(token, enc)
        }

        // 2. JSON string escapes, e.g. cnry....
        if (text.indexOf('\\') >= 0) {
            val un = Encodings.unescapeJson(text)
            if (un.length != text.length) scanDecoded(un, MarkerEncoding.JSON_ESCAPED)
        }
        // 3. Percent encoding, e.g. %63nry....
        if (text.indexOf('%') >= 0) {
            val dec = Encodings.percentDecode(text)
            if (dec.length != text.length) scanDecoded(dec, MarkerEncoding.URL_ENCODED)
        }
        // 4. Base64 runs. A decoded run is new text, so any occurrence in it is a genuine base64 hit;
        //    decoding the whole run covers every alignment of the token inside it.
        var runs = 0
        for (m in BASE64_RUN.findAll(text)) {
            if (++runs > MAX_BASE64_RUNS) break
            val decoded = Encodings.decodeBase64Run(m.value) ?: continue
            for (token in findRaw(decoded).keys) add(token, MarkerEncoding.BASE64)
        }
        return found
    }

    /**
     * Finds tracked tokens in plain text: one prefix search, then candidate lookup at each known
     * token length (so a token followed by more alphanumerics is still cut at its own length),
     * plus a direct search for each custom token.
     */
    private fun findRaw(text: String): Map<String, Found> {
        val lower = text.lowercase()
        val out = LinkedHashMap<String, Found>()

        fun record(token: String, at: Int) {
            val f = out.getOrPut(token) { Found() }
            f.total++
            if (text.regionMatches(at, token, 0, token.length)) f.exact++
        }

        val lengths = index.prefixedTokenLengths()
        if (lengths.isNotEmpty()) {
            val prefix = index.prefix
            var i = lower.indexOf(prefix)
            while (i >= 0) {
                var j = i + prefix.length
                while (j < lower.length && lower[j].isBase36()) j++
                val runLength = j - i
                for (len in lengths) {
                    if (len > runLength) continue
                    val candidate = lower.substring(i, i + len)
                    if (index.contains(candidate)) record(candidate, i)
                }
                i = lower.indexOf(prefix, i + 1)
            }
        }

        var customSeen = 0
        for (custom in index.customTokens()) {
            if (++customSeen > MAX_CUSTOM_TOKENS) break
            var at = lower.indexOf(custom)
            while (at >= 0) {
                record(custom, at)
                at = lower.indexOf(custom, at + custom.length)
            }
        }
        return out
    }

    /**
     * Tokens that appear inside a JSON string value under a tool-call key (or a descendant of one),
     * anywhere in the body. One such occurrence is enough, wherever else the token also appears.
     */
    private fun classifyToolCall(fragments: List<Any>, tokens: Set<String>): Set<String> {
        if (fragments.isEmpty()) return emptySet()
        val hits = HashSet<String>()
        for (root in fragments) {
            JsonSupport.walk(root, maxJsonDepth) { keys, value ->
                if (value !is String || hits.size == tokens.size) return@walk
                if (keys.none { it.lowercase() in TOOL_CALL_KEYS }) return@walk
                for (t in tokensIn(value).keys) if (t in tokens) hits.add(t)
            }
        }
        return hits
    }

    /**
     * Tokens inside a tool result: any string under an object recognised by its structure as one
     * ([TOOL_RESULT_ROLES], [TOOL_RESULT_TYPES]) or under a [TOOL_RESULT_KEYS] key. Keys only, never
     * the text, so a user message that merely mentions "tool" does not qualify.
     */
    private fun classifyToolResult(fragments: List<Any>, tokens: Set<String>): Set<String> {
        if (fragments.isEmpty()) return emptySet()
        val hits = HashSet<String>()
        fun collect(node: Any?) {
            JsonSupport.walk(node, maxJsonDepth) { _, v ->
                if (v is String && hits.size < tokens.size) for (t in tokensIn(v).keys) if (t in tokens) hits.add(t)
            }
        }
        for (root in fragments) {
            JsonSupport.walk(root, maxJsonDepth) { _, value ->
                if (value !is JSONObject || hits.size == tokens.size) return@walk
                if (isToolResultObject(value)) {
                    collect(value)
                    return@walk
                }
                for (k in value.keySet()) if (k.lowercase() in TOOL_RESULT_KEYS) collect(value.opt(k))
            }
        }
        return hits
    }

    private fun isToolResultObject(o: JSONObject): Boolean {
        var role: String? = null
        var type: String? = null
        for (k in o.keySet()) {
            when (k.lowercase()) {
                "role" -> role = (o.opt(k) as? String)?.lowercase()
                "type" -> type = (o.opt(k) as? String)?.lowercase()
            }
        }
        return role in TOOL_RESULT_ROLES || type in TOOL_RESULT_TYPES
    }

    private fun Char.isBase36(): Boolean = this in '0'..'9' || this in 'a'..'z'
}
