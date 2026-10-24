package com.canaryagent.markers

import burp.api.montoya.core.ToolType
import com.canaryagent.model.TrafficChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitPolicyTest {
    @Test
    fun `request-side matches from user-driven tools are never hits`() {
        for (tool in listOf(ToolType.REPEATER, ToolType.INTRUDER, ToolType.EXTENSIONS, ToolType.SCANNER)) {
            assertFalse(HitPolicy.shouldCount(TrafficChannel.HTTP_REQUEST, tool, isOrigin = false), tool.name)
            assertFalse(HitPolicy.shouldCount(TrafficChannel.WS_OUT, tool, isOrigin = false), tool.name)
        }
    }

    @Test
    fun `request-side matches from Proxy count unless they are the origin`() {
        assertTrue(HitPolicy.shouldCount(TrafficChannel.HTTP_REQUEST, ToolType.PROXY, isOrigin = false))
        assertTrue(HitPolicy.shouldCount(TrafficChannel.WS_OUT, ToolType.PROXY, isOrigin = false))
        assertFalse(HitPolicy.shouldCount(TrafficChannel.HTTP_REQUEST, ToolType.PROXY, isOrigin = true))
    }

    @Test
    fun `response-side and inbound WebSocket matches count from every tool`() {
        for (tool in ToolType.entries) {
            assertTrue(HitPolicy.shouldCount(TrafficChannel.HTTP_RESPONSE, tool, isOrigin = false), tool.name)
            assertTrue(HitPolicy.shouldCount(TrafficChannel.WS_IN, tool, isOrigin = false), tool.name)
            assertTrue(HitPolicy.shouldCount(TrafficChannel.COLLABORATOR, tool, isOrigin = false), tool.name)
        }
    }
}
