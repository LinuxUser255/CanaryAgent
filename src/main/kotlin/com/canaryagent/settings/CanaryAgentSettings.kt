package com.canaryagent.settings

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.ui.settings.SettingsPanelBuilder
import burp.api.montoya.ui.settings.SettingsPanelPersistence
import burp.api.montoya.ui.settings.SettingsPanelSetting
import burp.api.montoya.ui.settings.SettingsPanelWithData

/** Settings that influence annotation. Marker observation ignores all of them. */
interface AnnotationSettings {
    /** On: strong + medium heuristics run automatically. Off: only manually marked items are annotated. */
    val matchingEnabled: Boolean

    /** Restrict automatic annotation to Burp target scope (markers are still tracked everywhere). */
    val inScopeOnly: Boolean
}

/** Burp-backed settings panel (Settings -> Extensions -> CanaryAgent), persisted per project. */
class CanaryAgentSettings(private val api: MontoyaApi) : AnnotationSettings {
    companion object {
        const val MATCHING = "Automatically flag agent-related traffic (strong + medium heuristics)"
        const val IN_SCOPE_ONLY = "Only annotate in-scope traffic (markers are always tracked everywhere)"
        /**
         * Phrased as an opt-out on purpose: Burp returns false for a boolean setting that has no stored
         * value in a project saved before the key existed, whatever default is declared. With an opt-out,
         * "no stored value" means persistence stays on.
         */
        const val NO_PERSIST = "Do not persist markers and tool inventory in the project"
        const val COLLABORATOR_INTERVAL = "Collaborator polling interval in seconds (5-300)"
        const val DEFAULT_COLLABORATOR_INTERVAL = 10
        const val MIN_COLLABORATOR_INTERVAL = 5
        const val MAX_COLLABORATOR_INTERVAL = 300
    }

    private val panel: SettingsPanelWithData = SettingsPanelBuilder.settingsPanel()
        .withPersistence(SettingsPanelPersistence.PROJECT_SETTINGS)
        .withTitle("CanaryAgent")
        .withDescription("Annotation of LLM-agent traffic and tool inventory. Marker tracking is unaffected by these settings.")
        .withKeywords("canary", "agent", "llm", "tool", "marker")
        .withSettings(
            SettingsPanelSetting.booleanSetting(MATCHING, true),
            SettingsPanelSetting.booleanSetting(IN_SCOPE_ONLY, false),
            SettingsPanelSetting.booleanSetting(NO_PERSIST, false),
            SettingsPanelSetting.integerSetting(COLLABORATOR_INTERVAL, DEFAULT_COLLABORATOR_INTERVAL),
        )
        .build()

    /** Adds the panel under Settings -> Extensions; Burp releases the registration on unload. */
    fun register(): Registration = api.userInterface().registerSettingsPanel(panel)

    override val matchingEnabled: Boolean get() = panel.getBoolean(MATCHING)
    override val inScopeOnly: Boolean get() = panel.getBoolean(IN_SCOPE_ONLY)

    /** Read live at every flush. Off: nothing written; stored data is still restored at load until cleared. */
    val persistenceEnabled: Boolean get() = !panel.getBoolean(NO_PERSIST)

    /** Raw values as Burp reports them right now (the self-test prints this). */
    fun describe(): String =
        "matching=${panel.getBoolean(MATCHING)} inScopeOnly=${panel.getBoolean(IN_SCOPE_ONLY)} " +
            "noPersist=${panel.getBoolean(NO_PERSIST)} interval=${panel.getInteger(COLLABORATOR_INTERVAL)}"

    /** Read live before each poll; clamped so a typo cannot hammer the Collaborator server or stall polling. */
    val collaboratorIntervalSeconds: Long
        get() = panel.getInteger(COLLABORATOR_INTERVAL).coerceIn(MIN_COLLABORATOR_INTERVAL, MAX_COLLABORATOR_INTERVAL).toLong()
}
