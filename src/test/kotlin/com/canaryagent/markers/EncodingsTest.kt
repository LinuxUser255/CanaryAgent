package com.canaryagent.markers

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EncodingsTest {
    @Test
    fun `unescapeJson resolves unicode and simple escapes and leaves others alone`() {
        assertEquals("cnry \"x\" \\ / \n", Encodings.unescapeJson("\\u0063nry \\\"x\\\" \\\\ \\/ \\n"))
        assertEquals("\\q tail\\", Encodings.unescapeJson("\\q tail\\"))
        assertEquals("\\u12", Encodings.unescapeJson("\\u12"))
        assertEquals("plain", Encodings.unescapeJson("plain"))
    }

    @Test
    fun `percentDecode handles valid and invalid sequences`() {
        assertEquals("cnry a%zz%4", Encodings.percentDecode("%63nry%20a%zz%4"))
        assertEquals("plain", Encodings.percentDecode("plain"))
    }

    @Test
    fun `decodeBase64Run accepts padded, unpadded and url-safe input`() {
        val plain = "hello cnry7f3a9k2m world!"
        val std = Base64.getEncoder().encodeToString(plain.toByteArray())
        assertEquals(plain, Encodings.decodeBase64Run(std))
        assertEquals(plain, Encodings.decodeBase64Run(std.trimEnd('=')))
        val url = Base64.getUrlEncoder().withoutPadding().encodeToString("??>>$plain".toByteArray())
        assertEquals("??>>$plain", Encodings.decodeBase64Run(url))
    }

    @Test
    fun `decodeBase64Run drops a dangling char and rejects garbage`() {
        val std = Base64.getEncoder().withoutPadding().encodeToString("abcdef".toByteArray()) // 8 chars
        assertEquals("abcdef", Encodings.decodeBase64Run(std + "Q")) // 9 chars: dangling one dropped
        assertNull(Encodings.decodeBase64Run("ab"))
    }
}
