package com.canaryagent.annotation

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded side-map of [MessageAnalysis] results keyed by message content (F-review [4]). The HTTP
 * handler populates it as traffic flows through [TrafficAnnotator.analyze]; the context menu's
 * calls then become lookups instead of re-parsing up to 256 KB on the EDT. Analysis is a pure
 * function of the key, so sharing results across callers and toggle states is safe.
 */
class AnalysisCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2000
    }

    private val map = ConcurrentHashMap<String, MessageAnalysis>()

    /**
     * The cached analysis for [key], or [compute]'s result, stored. Under a race [compute] may run
     * more than once; it is pure, so the duplicates agree. The crude clear-when-full bound matches
     * the handler's pending map.
     */
    fun getOrCompute(key: String, compute: () -> MessageAnalysis): MessageAnalysis {
        map[key]?.let { return it }
        val computed = compute()
        if (map.size >= maxEntries) map.clear()
        map[key] = computed
        return computed
    }

    internal fun size(): Int = map.size
}
