package com.canaryagent.markers

import java.security.SecureRandom

/**
 * Generates marker tokens: fixed lowercase prefix plus a random base36 suffix, e.g. `cnry7f3a9k2m`.
 * Alphanumeric only, because LLMs tend to split or reformat punctuation.
 */
object MarkerGenerator {
    const val PREFIX = "cnry"
    const val DEFAULT_SUFFIX_LENGTH = 8
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val rng = SecureRandom()

    /** A fresh token: the prefix plus [suffixLength] base36 characters from [SecureRandom]. */
    fun next(suffixLength: Int = DEFAULT_SUFFIX_LENGTH): String {
        require(suffixLength >= DEFAULT_SUFFIX_LENGTH) { "suffix must be at least $DEFAULT_SUFFIX_LENGTH chars" }
        val sb = StringBuilder(PREFIX.length + suffixLength).append(PREFIX)
        repeat(suffixLength) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Generates a token that is not already present in [existing]. */
    fun nextUnique(suffixLength: Int = DEFAULT_SUFFIX_LENGTH, existing: (String) -> Boolean): String {
        while (true) {
            val t = next(suffixLength)
            if (!existing(t)) return t
        }
    }

    /** True for `cnry` + at least eight lowercase base36 characters. */
    fun isWellFormed(token: String): Boolean =
        token.length >= PREFIX.length + DEFAULT_SUFFIX_LENGTH &&
            token.startsWith(PREFIX) &&
            token.all { it in '0'..'9' || it in 'a'..'z' }
}
