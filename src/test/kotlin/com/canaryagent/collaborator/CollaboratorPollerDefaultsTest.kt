package com.canaryagent.collaborator

import com.canaryagent.settings.CanaryAgentSettings
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the Collaborator polling defaults to the 30-second floor PortSwigger recommends to avoid
 * unnecessary load on the Collaborator server (extension-portal review of issue #570, finding 6).
 */
class CollaboratorPollerDefaultsTest {

    @Test
    fun `settings default interval meets the 30s Collaborator floor`() {
        assertTrue(
            CanaryAgentSettings.DEFAULT_COLLABORATOR_INTERVAL >= 30,
            "DEFAULT_COLLABORATOR_INTERVAL must be >= 30s, was ${CanaryAgentSettings.DEFAULT_COLLABORATOR_INTERVAL}",
        )
    }

    @Test
    fun `poller fallback interval meets the 30s Collaborator floor`() {
        assertTrue(
            CollaboratorPoller.DEFAULT_INTERVAL_SECONDS >= 30L,
            "DEFAULT_INTERVAL_SECONDS must be >= 30s, was ${CollaboratorPoller.DEFAULT_INTERVAL_SECONDS}",
        )
    }

    @Test
    fun `configurable minimum meets the 30s Collaborator floor`() {
        assertTrue(
            CanaryAgentSettings.MIN_COLLABORATOR_INTERVAL >= 30,
            "MIN_COLLABORATOR_INTERVAL must be >= 30s, was ${CanaryAgentSettings.MIN_COLLABORATOR_INTERVAL}",
        )
    }

    @Test
    fun `poller fallback and settings default cannot drift apart`() {
        assertTrue(CollaboratorPoller.DEFAULT_INTERVAL_SECONDS == CanaryAgentSettings.DEFAULT_COLLABORATOR_INTERVAL.toLong())
    }

    @Test
    fun `settings default stays within the configurable range`() {
        assertTrue(
            CanaryAgentSettings.DEFAULT_COLLABORATOR_INTERVAL
                in CanaryAgentSettings.MIN_COLLABORATOR_INTERVAL..CanaryAgentSettings.MAX_COLLABORATOR_INTERVAL,
        )
    }
}
