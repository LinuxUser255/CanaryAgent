package com.canaryagent.collaborator

import com.canaryagent.collaborator.CollaboratorService.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientBootstrapTest {
    @Test
    fun `no stored key creates a new client`() {
        val (client, origin) = ClientBootstrap.resolve(null, restore = { "restored:$it" }, create = { "created" })
        assertEquals("created", client)
        assertEquals(Origin.CREATED, origin)
    }

    @Test
    fun `blank stored key is treated as absent`() {
        val (client, origin) = ClientBootstrap.resolve("   ", restore = { "restored:$it" }, create = { "created" })
        assertEquals("created", client)
        assertEquals(Origin.CREATED, origin)
    }

    @Test
    fun `stored key restores, trimmed`() {
        val (client, origin) = ClientBootstrap.resolve(" abc123 ", restore = { "restored:$it" }, create = { "created" })
        assertEquals("restored:abc123", client)
        assertEquals(Origin.RESTORED, origin)
    }

    @Test
    fun `rejected key falls back to a new client and reports the failure`() {
        var reported: RuntimeException? = null
        val (client, origin) = ClientBootstrap.resolve(
            "bad",
            restore = { throw IllegalArgumentException("invalid key") },
            create = { "created" },
            onRestoreFailed = { reported = it },
        )
        assertEquals("created", client)
        assertEquals(Origin.CREATED, origin)
        assertEquals("invalid key", reported?.message)
    }

    @Test
    fun `create failure propagates so the service can mark itself unavailable`() {
        val thrown = runCatching {
            ClientBootstrap.resolve<String>(null, restore = { it }, create = { throw IllegalStateException("disabled") })
        }.exceptionOrNull()
        assertEquals("disabled", thrown?.message)
        assertNull(thrown as? IllegalArgumentException)
    }
}
