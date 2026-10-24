package com.canaryagent

/** Loads sample bodies from src/test/resources/corpus. */
object Corpus {
    fun read(name: String): String =
        checkNotNull(Corpus::class.java.getResourceAsStream("/corpus/$name")) { "missing corpus file $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun contentTypeFor(name: String): String? = when {
        name.endsWith(".json") -> "application/json"
        name.startsWith("form_") || name.startsWith("bait_form") -> "application/x-www-form-urlencoded"
        name.contains("sse") -> "text/event-stream"
        name.contains("ndjson") -> "application/x-ndjson"
        name.contains("html") -> "text/html"
        else -> null
    }
}
