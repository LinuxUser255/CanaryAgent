package com.canaryagent.util

import burp.api.montoya.core.ByteArray as BurpByteArray

/** Decodes bytes 1:1 as ISO-8859-1 so every byte maps to exactly one char (offsets stay stable). */
fun BurpByteArray.toLatin1(): String = String(getBytes(), Charsets.ISO_8859_1)

/** The same 1:1 decoding for a JVM byte array. */
fun ByteArray.toLatin1(): String = String(this, Charsets.ISO_8859_1)
