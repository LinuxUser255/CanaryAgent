package com.canaryagent.annotation

import burp.api.montoya.core.Annotations
import burp.api.montoya.core.HighlightColor
import com.canaryagent.Corpus
import com.canaryagent.markers.MarkerMatch
import com.canaryagent.model.MarkerEncoding
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.model.AnnotatedTrafficItem
import com.canaryagent.model.AnnotationSource
import com.canaryagent.settings.AnnotationSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrafficAnnotatorTest {
    private class Settings(override var matchingEnabled: Boolean = true, override var inScopeOnly: Boolean = false) : AnnotationSettings

    private val inventory = ToolInventory()
    private val store = AnnotatedTrafficStore(capacity = 3)
    private val settings = Settings()
    private val annotator = TrafficAnnotator(BodyParser(), AgentTrafficDetector(), inventory, store, settings)

    @Test
    fun `analyze returns detection and tools, and recordTools feeds the inventory`() {
        val a = annotator.analyze(Corpus.read("openai_request_tools.json"), "application/json", "/v1/chat/completions")
        assertTrue(a.detection.strong)
        assertEquals(setOf("search_docs", "send_email"), a.tools.map { it.name }.toSet())
        annotator.recordTools(a, "https://api/v1/chat/completions")
        assertEquals(2, inventory.size())
    }

    @Test
    fun `note names tools and signals`() {
        val a = annotator.analyze(Corpus.read("openai_response_tool_calls.json"), "application/json", null)
        val note = annotator.noteFor(a)
        assertTrue(note.startsWith("CanaryAgent: tools present: search_docs ["), note)
        assertEquals(TrafficAnnotator.STRONG_COLOR, annotator.colorFor(a))
        val medium = annotator.analyze(Corpus.read("langchain_agent_action.json"), "application/json", null)
        assertTrue(annotator.noteFor(medium).startsWith("CanaryAgent: agent-like: web_search ["))
        assertEquals(TrafficAnnotator.MEDIUM_COLOR, annotator.colorFor(medium))
    }

    @Test
    fun `strong traffic without tools reads agent traffic, not tools present`() {
        // A chat-history response is strong (conversation array) but offers no tools; "tools present"
        // with an empty list misled testers (F10 check, 2026-10-22).
        val a = annotator.analyze(Corpus.read("chat_history_response.json"), "application/json", null)
        assertTrue(a.detection.strong)
        assertTrue(a.tools.isEmpty())
        val note = annotator.noteFor(a)
        assertTrue(note.startsWith("CanaryAgent: agent traffic ["), note)
        assertFalse(note.contains("tools present"), note)
    }

    @Test
    fun `settings gate automatic annotation`() {
        assertTrue(annotator.shouldAutoAnnotate(inScope = false))
        settings.inScopeOnly = true
        assertFalse(annotator.shouldAutoAnnotate(inScope = false))
        assertTrue(annotator.shouldAutoAnnotate(inScope = true))
        settings.matchingEnabled = false
        assertFalse(annotator.shouldAutoAnnotate(inScope = true))
    }

    @Test
    fun `notes append once`() {
        assertEquals("x", annotator.appendNote(null, "x"))
        assertEquals("old | x", annotator.appendNote("old", "x"))
        assertEquals("old | x", annotator.appendNote("old | x", "x"))
    }

    @Test
    fun `marker hit note names each token with its context`() {
        val hits = listOf(
            MarkerMatch("cnryaaaaaaaa", setOf(MarkerEncoding.RAW), inToolCall = true, inToolResult = true),
            MarkerMatch("cnrybbbbbbbb", setOf(MarkerEncoding.RAW), inToolCall = false, inToolResult = true),
            MarkerMatch("cnrycccccccc", setOf(MarkerEncoding.RAW), inToolCall = false),
        )
        // Labelled tokens first, entries separated by "; " so a label cannot read as covering its
        // neighbour ("a, b (tool result)" looked like both were in the tool result; F10 check).
        assertEquals(
            "CanaryAgent: marker hit: cnryaaaaaaaa (tool call); cnrybbbbbbbb (tool result); cnrycccccccc",
            TrafficAnnotator.markerHitNote(hits),
        )
    }

    @Test
    fun `marker hit note puts labelled tokens before plain ones`() {
        val hits = listOf(
            MarkerMatch("cnryplainaaa", setOf(MarkerEncoding.RAW), inToolCall = false),
            MarkerMatch("cnryresultbb", setOf(MarkerEncoding.RAW), inToolCall = false, inToolResult = true),
        )
        assertEquals(
            "CanaryAgent: marker hit: cnryresultbb (tool result); cnryplainaaa",
            TrafficAnnotator.markerHitNote(hits),
        )
    }

    @Test
    fun `a Logger++ placeholder is not a note and survives at the end`() {
        // Logger++ removes its "$LPP:<id>$" tag later; what is left must be a clean note.
        assertEquals("x\$LPP:1427564829\$", TrafficAnnotator.joinNotes("\$LPP:1427564829\$", "x"))
        assertEquals("old | x\$LPP:7\$", TrafficAnnotator.joinNotes("old \$LPP:7\$", "x"))
        assertEquals("\$LPP:7\$old | x", TrafficAnnotator.joinNotes("\$LPP:7\$old | x", "x"))
        assertEquals("y | x\$LPP:1\$\$LPP:2\$", TrafficAnnotator.joinNotes("\$LPP:1\$y\$LPP:2\$", "x"))
    }

    @Test
    fun `annotate returns the same instance when the note and colour are already there`() {
        val note = "CanaryAgent: tools present: x [tool keys]"
        val empty = StubAnnotations(null, null)
        val first = annotator.annotate(empty, note, TrafficAnnotator.STRONG_COLOR)
        assertTrue(first !== empty)
        assertEquals(note, first.notes())
        assertEquals(TrafficAnnotator.STRONG_COLOR, first.highlightColor())
        // Second stage (the response) sees the request-stage annotations: nothing new, same object back.
        assertTrue(annotator.annotate(first, note, TrafficAnnotator.STRONG_COLOR) === first)
        // Existing colour is kept unless a marker hit raises it; a new note still yields a copy.
        val again = annotator.annotate(first, "CanaryAgent: marker hit: cnry7f3a9k2m", TrafficAnnotator.MARKER_COLOR)
        assertEquals("$note | CanaryAgent: marker hit: cnry7f3a9k2m", again.notes())
        assertEquals(TrafficAnnotator.MARKER_COLOR, again.highlightColor())
        assertTrue(annotator.annotate(again, note, TrafficAnnotator.MEDIUM_COLOR) === again)
    }

    /** Montoya's Annotations factory needs a running Burp; this stub mirrors the copy-on-write contract. */
    private class StubAnnotations(private var notes: String?, private var color: HighlightColor?) : Annotations {
        override fun notes(): String? = notes
        override fun hasNotes(): Boolean = !notes.isNullOrEmpty()
        override fun hasHighlightColor(): Boolean = color != null && color != HighlightColor.NONE
        override fun setNotes(notes: String?) { this.notes = notes }
        override fun highlightColor(): HighlightColor? = color
        override fun setHighlightColor(highlightColor: HighlightColor?) { color = highlightColor }
        override fun withNotes(notes: String?): Annotations = StubAnnotations(notes, color)
        override fun withHighlightColor(highlightColor: HighlightColor?): Annotations = StubAnnotations(notes, highlightColor)
    }

    @Test
    fun `traffic store merges entries for the same message in arrival order`() {
        fun item(id: Long, note: String, source: AnnotationSource, key: String) =
            AnnotatedTrafficItem(id, id, "Proxy", "POST", "https://h/chat", note, source, messageKey = key)
        val first = store.add { id -> item(id, "CanaryAgent: agent-like [agent path]", AnnotationSource.AUTO, "#9") }
        val merged = store.add { id -> item(id, "CanaryAgent: marker hit: cnry7f3a9k2m", AnnotationSource.MARKER_HIT, "#9") }
        store.add { id -> item(id, "CanaryAgent: agent-like [agent path]", AnnotationSource.AUTO, "") }
        store.add { id -> item(id, "CanaryAgent: agent-like [agent path]", AnnotationSource.AUTO, "") }
        assertEquals(first.id, merged.id)
        assertEquals("CanaryAgent: agent-like [agent path] | CanaryAgent: marker hit: cnry7f3a9k2m", merged.note)
        assertEquals(AnnotationSource.MARKER_HIT, merged.source)
        assertEquals(3, store.size()) // keyless items never merge
        // Repeating a note does not duplicate it inside the row.
        assertEquals(merged.note, store.add { id -> item(id, "CanaryAgent: marker hit: cnry7f3a9k2m", AnnotationSource.MARKER_HIT, "#9") }.note)
    }

    @Test
    fun `traffic store is bounded and ordered`() {
        for (i in 1..5) store.add { id -> AnnotatedTrafficItem(id, i.toLong(), "Proxy", "GET", "https://h/$i", "n", AnnotationSource.AUTO) }
        assertEquals(listOf("https://h/3", "https://h/4", "https://h/5"), store.snapshot().map { it.url })
        assertEquals(5L, store.snapshot().last().id)
    }
}
