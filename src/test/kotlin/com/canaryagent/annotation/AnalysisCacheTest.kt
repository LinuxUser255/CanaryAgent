package com.canaryagent.annotation

import burp.api.montoya.http.message.requests.HttpRequest
import com.canaryagent.Corpus
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.settings.AnnotationSettings
import java.lang.reflect.Proxy
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisCacheTest {

    @Test
    fun `compute runs once per key`() {
        val cache = AnalysisCache()
        var computed = 0
        repeat(3) { cache.getOrCompute("k") { computed++; MessageAnalysis.NONE } }
        assertEquals(1, computed)
    }

    @Test
    fun `distinct keys compute separately and the bound clears instead of growing`() {
        val cache = AnalysisCache(maxEntries = 2)
        for (i in 1..5) cache.getOrCompute("k$i") { MessageAnalysis.NONE }
        assertTrue(cache.size() <= 2, "cache must stay bounded, was ${cache.size()}")
        var computed = 0
        cache.getOrCompute("fresh") { computed++; MessageAnalysis.NONE }
        cache.getOrCompute("fresh") { computed++; MessageAnalysis.NONE }
        assertEquals(1, computed)
    }

    // ---- memoization through TrafficAnnotator --------------------------------------------------

    private class Settings(override var matchingEnabled: Boolean = true, override var inScopeOnly: Boolean = false) : AnnotationSettings

    private val annotator = TrafficAnnotator(BodyParser(), AgentTrafficDetector(), ToolInventory(), AnnotatedTrafficStore(capacity = 3), Settings())

    /** Just enough of a request for the analyze overload: body, Content-Type and path. */
    private fun fakeRequest(body: String, contentType: String? = "application/json", path: String = "/v1/chat/completions"): HttpRequest =
        Proxy.newProxyInstance(HttpRequest::class.java.classLoader, arrayOf(HttpRequest::class.java)) { self, m, args ->
            when (m.name) {
                "bodyToString" -> body
                "headerValue" -> if ((args!![0] as String).equals("Content-Type", ignoreCase = true)) contentType else null
                "path" -> path
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                "toString" -> "FakeRequest"
                else -> throw UnsupportedOperationException("fakeRequest does not implement ${m.name}")
            }
        } as HttpRequest

    @Test
    fun `a second analyze of the same message is a lookup, not a parse`() {
        val request = fakeRequest(Corpus.read("openai_request_tools.json"))
        val first = annotator.analyze(request)
        assertTrue(first.isAgentRelated, "sanity: the corpus body is agent traffic")
        // Misleading precomputed fragments: a fresh parse of them would find nothing. A cached
        // implementation returns the first result; an uncached one would return NONE.
        val second = annotator.analyze(request, fragments = listOf(JSONObject("""{"unrelated":true}""")))
        assertEquals(first, second, "the second call must come from the cache")
    }

    @Test
    fun `a different message is not served from the cache`() {
        val agent = annotator.analyze(fakeRequest(Corpus.read("openai_request_tools.json")))
        assertTrue(agent.isAgentRelated)
        val other = annotator.analyze(fakeRequest("""{"plain":"chat"}"""))
        assertEquals(MessageAnalysis.NONE, other)
    }
}
