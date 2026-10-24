package com.canaryagent.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Short local-time stamps for log lines. */
object Timestamps {
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** `HH:mm:ss.SSS` in the system zone, as the HIT and PLACED log lines use. */
    fun hms(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(HMS)
}
