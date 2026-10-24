package com.canaryagent.inventory

import com.canaryagent.Corpus
import com.canaryagent.annotation.BodyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolExtractorTest {
    private val parser = BodyParser()

    private fun tools(name: String): Map<String, Set<String>> =
        ToolExtractor.extract(parser.parse(Corpus.read(name), Corpus.contentTypeFor(name))).associate { it.name to it.parameterKeys }

    @Test
    fun `openai tool definitions expose names and property keys`() {
        val t = tools("openai_request_tools.json")
        assertEquals(setOf("query", "limit"), t["search_docs"])
        assertEquals(setOf("to", "subject", "body"), t["send_email"])
    }

    @Test
    fun `parameter keys are sorted alphabetically`() {
        // org.json does not preserve source order, so keys are sorted for a deterministic display.
        assertEquals(listOf("body", "subject", "to"), tools("openai_request_tools.json")["send_email"]!!.toList())
        assertEquals(listOf("limit", "query"), tools("openai_request_tools.json")["search_docs"]!!.toList())
    }

    @Test
    fun `openai tool calls parse stringified arguments`() {
        assertEquals(mapOf("search_docs" to setOf("query", "limit")), tools("openai_response_tool_calls.json"))
    }

    @Test
    fun `sse deltas merge name and later arguments`() {
        val t = tools("openai_sse_tool_calls.txt")
        assertEquals(setOf("get_weather"), t.keys)
        assertEquals(emptySet(), t["get_weather"]) // arguments arrive as fragments; keys are not visible
    }

    @Test
    fun `anthropic input_schema and tool_use blocks`() {
        assertEquals(mapOf("fetch_url" to setOf("url", "timeout")), tools("anthropic_request_tools.json"))
        assertEquals(mapOf("lookup_customer" to setOf("email", "include_orders")), tools("anthropic_response_tool_use.json"))
    }

    @Test
    fun `framework and ndjson shapes`() {
        assertEquals(mapOf("web_search" to setOf("query")), tools("langchain_agent_action.json"))
        assertEquals(mapOf("get_current_weather" to setOf("format", "location")), tools("ndjson_ollama_stream.txt"))
        assertEquals(setOf("create_ticket", "close_ticket"), tools("custom_available_tools.json").keys)
    }

    @Test
    fun `bait yields no tools`() {
        for (f in listOf("bait_user_profile.json", "bait_inbox_messages.json", "bait_role_outside_messages.json", "bait_products.json")) {
            assertTrue(tools(f).isEmpty(), "$f -> ${tools(f)}")
        }
    }

    @Test
    fun `tool names must look like identifiers`() {
        val roots = parser.parse("""{"tools":[{"name":"Jane Doe","parameters":{"type":"object","properties":{"a":{}}}}]}""", "application/json")
        assertTrue(ToolExtractor.extract(roots).isEmpty())
    }
}
