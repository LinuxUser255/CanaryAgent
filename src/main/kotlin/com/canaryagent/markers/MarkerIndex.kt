package com.canaryagent.markers

/** Read-only view of placed markers that the scanner consults. Implemented by [MarkerTracker]. */
interface MarkerIndex {
    /** Fixed prefix of generated tokens, lowercase. */
    val prefix: String

    /** True if [token] (lowercase) is a tracked marker. */
    fun contains(token: String): Boolean

    /** Distinct total lengths of tracked prefixed tokens, so candidates can be cut at known lengths. */
    fun prefixedTokenLengths(): Set<Int>

    /** Tracked tokens that do not start with [prefix] (custom text, Collaborator ids). Lowercase. */
    fun customTokens(): Collection<String>
}
