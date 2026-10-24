package com.canaryagent.util

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Small helpers around org.json for parsing untrusted bodies with guards.
 * Callers are expected to have applied a size cap before parsing.
 */
object JsonSupport {
    const val DEFAULT_MAX_DEPTH = 12

    /** Parses [text] if it is a JSON object or array; returns null on anything else (never throws). */
    fun parseLenient(text: String): Any? {
        val t = text.trim()
        if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return null
        return try {
            JSONTokener(t).nextValue().takeIf { it is JSONObject || it is JSONArray }
        } catch (_: JSONException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    /**
     * Parses a body that is plain JSON, or a server-sent-events stream whose `data:` lines carry JSON.
     * Lines that are not JSON (e.g. `data: [DONE]`) are skipped.
     */
    fun parseFragments(text: String): List<Any> {
        val ndjson = parseNdjson(text)
        if (ndjson.isNotEmpty()) return ndjson
        parseLenient(text)?.let { return listOf(it) }
        if (!text.contains("data:")) return emptyList()
        val out = ArrayList<Any>()
        for (line in text.lineSequence()) {
            val l = line.trimStart()
            if (!l.startsWith("data:")) continue
            val payload = l.substring("data:".length).trim()
            if (payload == "[DONE]") continue // OpenAI-style end-of-stream sentinel
            parseLenient(payload)?.let(out::add)
        }
        return out
    }

    /** Newline-delimited JSON (e.g. Ollama streaming): two or more lines that each parse on their own. */
    private fun parseNdjson(text: String): List<Any> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2 || lines.any { it[0] != '{' && it[0] != '[' }) return emptyList()
        val parsed = lines.map { parseLenient(it) ?: return emptyList() }
        return parsed
    }

    /**
     * Depth-limited pre-order walk. [visitor] receives the chain of ancestor object keys
     * (array indices are not included) and the node, for every node including containers.
     */
    fun walk(root: Any?, maxDepth: Int = DEFAULT_MAX_DEPTH, visitor: (keys: List<String>, value: Any?) -> Unit) {
        fun go(node: Any?, keys: List<String>, depth: Int) {
            visitor(keys, node)
            if (depth >= maxDepth) return
            when (node) {
                is JSONObject -> for (k in node.keySet()) go(node.opt(k), keys + k, depth + 1)
                is JSONArray -> for (i in 0 until node.length()) go(node.opt(i), keys, depth + 1)
            }
        }
        go(root, emptyList(), 0)
    }
}
