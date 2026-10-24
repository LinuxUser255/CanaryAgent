package com.canaryagent.selftest

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import com.canaryagent.CanaryAgentRuntime
import com.canaryagent.settings.AnnotationSettings

/**
 * Dev-only entry point: starts the normal runtime under the name "CanaryAgent (self-test)" and adds
 * a tab that exercises it against public echo services through Burp's networking. Never shipped in
 * the production JAR.
 *
 * `-Dcanaryagent.selftest.forceMatching=true` makes "Automatically flag agent-related traffic" read
 * as ON whatever Burp stores: a headless Burp never renders the settings panel, so the value is unset
 * and A5/A8 would skip. Every other setting is still read live from Burp.
 */
class SelfTestExtension : BurpExtension {
    companion object {
        const val PROP_FORCE_MATCHING = "canaryagent.selftest.forceMatching"
    }

    override fun initialize(api: MontoyaApi?) {
        val montoya = requireNotNull(api) { "MontoyaApi must not be null" }
        val forceMatching = System.getProperty(PROP_FORCE_MATCHING) == "true"
        val runtime = CanaryAgentRuntime.start(montoya, "CanaryAgent (self-test)") { live ->
            if (!forceMatching) live else object : AnnotationSettings {
                override val matchingEnabled: Boolean get() = true
                override val inScopeOnly: Boolean get() = live.inScopeOnly
            }
        }
        val tab = SelfTestTab(runtime)
        montoya.userInterface().registerSuiteTab("CanaryAgent Self-Test", tab.component)
        val stats = SelfTestStats.startIfConfigured(runtime)
        montoya.extension().registerUnloadingHandler {
            stats?.dispose()
            tab.dispose()
        }
        runtime.log.info("Self-test tab registered. Open 'CanaryAgent Self-Test' and press Run.")
    }
}
