package com.canaryagent.annotation

import com.canaryagent.util.JsonSupport
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads Burp's exported project options and reports whether its streaming-responses settings would
 * hide server-sent-events responses from extensions. Streamed responses never reach the HttpHandler,
 * so markers and tools inside them go unseen (verified in Burp Pro 2026.9).
 *
 * Burp 2026.9 exports `project_options.http.streaming_responses` with `use_text_event_stream_header`
 * (boolean) and `urls[]` of `{enabled, include_subdomains, prefix}` (confirmed via self-test E1).
 * The lookup stays tolerant so a renamed key degrades to "no hint" rather than a crash: it finds the
 * first object whose key contains "streaming", then inspects booleans whose key mentions "event" and
 * any arrays of prefixes inside it.
 */
object StreamingSettingsHint {

    /** What the streaming-responses section says and whether it hides SSE responses from extensions. */
    data class Finding(
        /** "Treat responses with the text/event-stream MIME type as streaming" is on. */
        val eventStreamAsStreaming: Boolean,
        /** Enabled prefixes in the streaming-responses list. */
        val prefixes: List<String>,
        /** JSON key path where the section was found, for the log line and the self-test. */
        val sectionKey: String,
    ) {
        val hidesSseResponses: Boolean get() = eventStreamAsStreaming || prefixes.isNotEmpty()

        /** The one WARNING line logged at load when [hidesSseResponses] is true. */
        fun message(): String = buildString {
            append("Burp's streaming-responses settings (Settings -> Network -> HTTP -> Streaming responses) ")
            append("hide streamed responses from extensions, so markers and tools in SSE LLM responses will be missed. ")
            if (eventStreamAsStreaming) append("'Treat responses with the text/event-stream MIME type as streaming' is ON; untick it while testing. ")
            if (prefixes.isNotEmpty()) append("Streaming prefixes: ${prefixes.joinToString(", ")}. ")
            append("(docs/DEVELOPMENT.md section 6)")
        }
    }

    /** Parses the JSON returned by `exportProjectOptionsAsJson`. Null when no streaming section is found. */
    fun analyze(projectOptionsJson: String): Finding? {
        val root = JsonSupport.parseLenient(projectOptionsJson) as? JSONObject ?: return null
        val (key, section) = findStreamingSection(root, "") ?: return null
        val flags = ArrayList<Boolean>()
        val prefixes = ArrayList<String>()
        collect(section, flags, prefixes)
        return Finding(eventStreamAsStreaming = flags.any { it }, prefixes = prefixes, sectionKey = key)
    }

    private fun findStreamingSection(obj: JSONObject, path: String, depth: Int = 0): Pair<String, JSONObject>? {
        if (depth > 8) return null
        for (k in obj.keySet()) {
            val child = obj.opt(k)
            val childPath = if (path.isEmpty()) k else "$path.$k"
            if (child is JSONObject) {
                if (k.lowercase().contains("streaming")) return childPath to child
                findStreamingSection(child, childPath, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun collect(node: Any?, flags: MutableList<Boolean>, prefixes: MutableList<String>, keyHint: String = "") {
        when (node) {
            is JSONObject -> for (k in node.keySet()) {
                val v = node.opt(k)
                val lk = k.lowercase()
                if (v is Boolean && lk.contains("event")) flags += v
                else collect(v, flags, prefixes, lk)
            }
            is JSONArray -> for (i in 0 until node.length()) {
                when (val e = node.opt(i)) {
                    is String -> if (e.isNotBlank()) prefixes += e
                    is JSONObject -> {
                        val enabled = e.keySet().firstOrNull { it.equals("enabled", true) }?.let { e.optBoolean(it, true) } ?: true
                        val prefix = e.keySet().firstOrNull { it.lowercase() in PREFIX_KEYS }?.let { e.optString(it) }
                        if (enabled && !prefix.isNullOrBlank()) prefixes += prefix
                        else if (prefix == null) collect(e, flags, prefixes, keyHint)
                    }
                }
            }
        }
    }

    private val PREFIX_KEYS = setOf("prefix", "host", "url", "value")
}
