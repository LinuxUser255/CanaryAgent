package com.canaryagent.util

import java.security.MessageDigest

/** SHA-256 helpers for identifying message bodies and sessions without storing their content. */
object Hashing {
    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Lowercase hex SHA-256 of [text] as UTF-8. */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** First eight hex chars; enough to group or label, not to identify. */
    fun short(bytes: ByteArray): String = sha256Hex(bytes).take(8)

    /** [short] of [text] as UTF-8. */
    fun short(text: String): String = short(text.toByteArray(Charsets.UTF_8))
}
