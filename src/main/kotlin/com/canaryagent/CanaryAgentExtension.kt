package com.canaryagent

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi

/**
 * Entry point for the CanaryAgent Burp Suite extension. All wiring lives in [CanaryAgentRuntime].
 */
class CanaryAgentExtension : BurpExtension {
    override fun initialize(api: MontoyaApi?) {
        val montoya = requireNotNull(api) { "MontoyaApi must not be null" }
        CanaryAgentRuntime.start(montoya)
    }
}
