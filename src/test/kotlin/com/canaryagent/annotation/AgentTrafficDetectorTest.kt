package com.canaryagent.annotation

import com.canaryagent.Corpus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTrafficDetectorTest {
    private val parser = BodyParser()
    private val detector = AgentTrafficDetector()

    private fun detect(name: String, path: String? = null): Detection =
        detector.detect(parser.parse(Corpus.read(name), Corpus.contentTypeFor(name)), path)

    @Test
    fun `agent payloads are strong`() {
        for (f in listOf(
            "openai_request_tools.json", "openai_response_tool_calls.json", "openai_sse_tool_calls.txt",
            "anthropic_request_tools.json", "anthropic_response_tool_use.json", "ndjson_ollama_stream.txt",
            "custom_available_tools.json", "form_encoded_messages.txt",
        )) {
            val d = detect(f)
            assertTrue(d.strong, "$f should be strong, got ${d.signals}")
        }
    }

    @Test
    fun `framework payload without standard keys is medium`() {
        val d = detect("langchain_agent_action.json")
        assertTrue(d.isAgentRelated)
        assertFalse(d.strong, "${d.signals}")
        assertTrue(Signal.FRAMEWORK_KEYS in d.signals)
    }

    @Test
    fun `false-positive bait is not flagged`() {
        for (f in listOf(
            "bait_user_profile.json", "bait_inbox_messages.json", "bait_role_outside_messages.json",
            "bait_products.json", "bait_html.txt", "bait_form_login.txt",
        )) {
            val d = detect(f)
            assertFalse(d.isAgentRelated, "$f should not be flagged, got ${d.signals}")
        }
    }

    @Test
    fun `specific signals fire`() {
        assertTrue(Signal.MESSAGES_ARRAY in detect("openai_request_tools.json").signals)
        assertTrue(Signal.TOOL_SCHEMA in detect("openai_request_tools.json").signals)
        assertTrue(Signal.FUNCTION_OBJECT in detect("openai_response_tool_calls.json").signals)
        assertTrue(Signal.TOOL_USE_RESULT in detect("anthropic_response_tool_use.json").signals)
        assertTrue(Signal.CONVERSATION_ARRAY in detect("custom_available_tools.json").signals)
    }

    /**
     * F5: a conversation is recognised by its roles, not by the key it sits under. `chat_history`,
     * `history` and the like were medium only because they are not named `messages`.
     */
    @Test
    fun `role array with an llm role is strong under any key`() {
        val d = detect("chat_history_response.json")
        assertTrue(d.strong, "${d.signals}")
        assertTrue(Signal.CONVERSATION_ARRAY in d.signals)
        val history = parser.parse("""{"history":[{"role":"system","content":"You are helpful"}]}""", "application/json")
        assertTrue(detector.detect(history).strong)
        val topLevel = parser.parse("""[{"role":"developer","content":"x"},{"role":"user","content":"y"}]""", "application/json")
        assertTrue(detector.detect(topLevel).strong)
    }

    /** A user-only role array is what a ticketing or comments app could send: still flagged, but medium. */
    @Test
    fun `user-only role array stays medium`() {
        val d = detect("bait_user_only_roles.json")
        assertTrue(d.isAgentRelated)
        assertFalse(d.strong, "${d.signals}")
        assertEquals(setOf(Signal.ROLE_ARRAY), d.signals)
    }

    @Test
    fun `agent path only counts with a body signal`() {
        val weak = parser.parse("""{"model":"x","input":"hi"}""", "application/json")
        assertEquals(setOf(Signal.AGENT_PATH), detector.detect(weak, "/agents/run").signals)
        assertTrue(detector.detect(weak, "/api/products").signals.isEmpty())
        val unrelated = parser.parse("""{"sku":"x"}""", "application/json")
        assertTrue(detector.detect(unrelated, "/v1/messages").signals.isEmpty())
    }

    @Test
    fun `tool_call_id with content is strong`() {
        val roots = parser.parse("""{"role":"tool","tool_call_id":"c1","content":"42"}""", "application/json")
        assertTrue(Signal.TOOL_CALL_ID in detector.detect(roots).signals)
    }

    @Test
    fun `depth limit stops the walk`() {
        val deep = buildString { repeat(20) { append("{\"n\":") }; append("{\"tools\":[]}"); repeat(20) { append("}") } }
        val roots = parser.parse(deep, "application/json")
        assertTrue(AgentTrafficDetector(maxDepth = 5).detect(roots).signals.isEmpty())
        assertTrue(AgentTrafficDetector(maxDepth = 30).detect(roots).strong)
    }
}
