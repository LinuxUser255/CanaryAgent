package com.canaryagent

import burp.api.montoya.MontoyaApi
import com.canaryagent.annotation.AgentTrafficDetector
import com.canaryagent.annotation.AnnotatedTrafficStore
import com.canaryagent.annotation.BodyParser
import com.canaryagent.annotation.StreamingSettingsHint
import com.canaryagent.annotation.TrafficAnnotator
import com.canaryagent.collaborator.CollaboratorPoller
import com.canaryagent.collaborator.CollaboratorService
import com.canaryagent.contextmenu.CanaryAgentContextMenuProvider
import com.canaryagent.handlers.HttpObservationHandler
import com.canaryagent.handlers.ObservationExecutor
import com.canaryagent.handlers.ProxyWebSocketObservationHandler
import com.canaryagent.handlers.WebSocketMarkerObserver
import com.canaryagent.handlers.WebSocketObservationHandler
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.inventory.ToolInventoryLogger
import com.canaryagent.markers.MarkerHitLogger
import com.canaryagent.markers.MarkerScanner
import com.canaryagent.markers.MarkerTracker
import burp.api.montoya.scanner.scancheck.ScanCheckType
import com.canaryagent.persistence.MontoyaNode
import com.canaryagent.persistence.PersistenceService
import com.canaryagent.scanner.MarkerPassiveScanCheck
import com.canaryagent.settings.AnnotationSettings
import com.canaryagent.settings.CanaryAgentSettings
import com.canaryagent.ui.CanaryAgentTab
import com.canaryagent.util.CanaryLog
import com.canaryagent.util.Capabilities

/**
 * Everything the extension wires up at load time, in one place, so that both the real entry point
 * and the dev-only self-test entry point start the same runtime. Components are exposed read-only
 * for the self-test; production code never reaches in.
 */
class CanaryAgentRuntime private constructor(
    val api: MontoyaApi,
    val log: CanaryLog,
    val capabilities: Capabilities,
    val settings: CanaryAgentSettings,
    /** The annotation settings the annotator actually consults: [settings], unless the entry point wrapped them. */
    val annotationSettings: AnnotationSettings,
    val tracker: MarkerTracker,
    val scanner: MarkerScanner,
    val inventory: ToolInventory,
    val traffic: AnnotatedTrafficStore,
    val annotator: TrafficAnnotator,
    val collaborator: CollaboratorService?,
    private val poller: CollaboratorPoller?,
    private val tab: CanaryAgentTab,
    val persistence: PersistenceService?,
    private val observationExecutor: ObservationExecutor,
) {
    companion object {
        const val EXTENSION_NAME = "CanaryAgent"

        /**
         * Registers handlers, menu, tab, settings and the unloading handler. Call once from `initialize`.
         * [wrapAnnotationSettings] lets the dev-only self-test entry point decorate the live Burp settings
         * (a headless Burp never renders the settings panel, so every value reads as unset); production
         * passes nothing and the annotator reads Burp directly.
         */
        fun start(
            api: MontoyaApi,
            extensionName: String = EXTENSION_NAME,
            wrapAnnotationSettings: (AnnotationSettings) -> AnnotationSettings = { it },
        ): CanaryAgentRuntime {
            api.extension().setName(extensionName)
            val log = CanaryLog(api.logging())
            val capabilities = Capabilities.detect(api)

            val settings = CanaryAgentSettings(api)
            settings.register()
            val annotationSettings = wrapAnnotationSettings(settings)
            // Settings are never read here: Burp returns stale values during initialize. Every consumer
            // reads them live (per message, per flush, per poll tick).

            val tracker = MarkerTracker()
            val scanner = MarkerScanner(tracker)
            tracker.addListener(MarkerHitLogger(log))

            val inventory = ToolInventory()
            val traffic = AnnotatedTrafficStore()
            val annotator = TrafficAnnotator(BodyParser(), AgentTrafficDetector(), inventory, traffic, annotationSettings)

            // Restore stored markers/tools before any handler or logger can observe the stores.
            val persistence = startPersistence(api, tracker, inventory, log) { settings.persistenceEnabled }
            inventory.addListener(ToolInventoryLogger(log))

            // Off-handler lane for tracker/store/inventory writes (F-review [3]): the handlers return as
            // soon as the annotations are composed; recording degrades to inline under load, never drops.
            val observationExecutor = ObservationExecutor()
            val wsObserver = WebSocketMarkerObserver(tracker, scanner, log, { payload -> annotator.isAgentShaped(payload, null, null) }, observationExecutor)
            api.http().registerHttpHandler(HttpObservationHandler(tracker, scanner, annotator, log, executor = observationExecutor))
            api.websockets().registerWebSocketCreatedHandler(WebSocketObservationHandler(wsObserver))
            api.proxy().registerWebSocketCreationHandler(ProxyWebSocketObservationHandler(wsObserver))

            // Additive Scanner integration (Professional): audit issues for marker hits. Detection never
            // depends on it - the handlers above observe all traffic on every edition. Read-only against
            // the tracker, so audits cannot inflate hit counts.
            if (capabilities.scannerAvailable) {
                api.scanner().registerPassiveScanCheck(MarkerPassiveScanCheck(tracker, scanner, log), ScanCheckType.PER_REQUEST)
                log.info("Scanner integration: passive check registered; audits report marker hits as issues.")
            }

            // Collaborator is Professional-only; on Community the service is null and its actions are hidden.
            // The client key lives in the project so a reload restores the same client and keeps fetching
            // interactions for payloads placed earlier. Restore happens before the poller starts.
            val collaborator = if (capabilities.collaboratorAvailable) {
                CollaboratorService(
                    api, log,
                    loadKey = { persistence?.loadCollaboratorKey() },
                    saveKey = { key -> persistence?.saveCollaboratorKey(key) },
                ).also { service ->
                    val origin = service.restoreIfStored()
                    if (service.isAvailable()) {
                        when (origin) {
                            CollaboratorService.Origin.RESTORED -> log.info("Collaborator: client restored from the project; earlier payloads keep polling.")
                            CollaboratorService.Origin.CREATED -> log.info("Collaborator: stored key rejected; a new client was created and its key saved.")
                            CollaboratorService.Origin.NONE -> log.info("Collaborator: no stored client; one is created on first payload and its key saved to the project.")
                        }
                    }
                }
            } else null
            val poller = collaborator?.let {
                CollaboratorPoller(it, tracker, log) { settings.collaboratorIntervalSeconds }.also { p -> p.start() }
            }

            api.userInterface().registerContextMenuItemsProvider(
                CanaryAgentContextMenuProvider(api, tracker, annotator, collaborator, log),
            )
            val tab = CanaryAgentTab(api, tracker, inventory, traffic)
            api.userInterface().registerSuiteTab(EXTENSION_NAME, tab.component)

            val runtime = CanaryAgentRuntime(
                api, log, capabilities, settings, annotationSettings, tracker, scanner, inventory, traffic, annotator, collaborator, poller, tab, persistence, observationExecutor,
            )
            api.extension().registerUnloadingHandler { runtime.dispose() }
            warnIfStreamingHidesSse(api, log)

            log.info(
                "Loaded on Burp ${capabilities.edition}; Collaborator actions ${if (collaborator != null) "enabled" else "hidden (Community)"}. " +
                    "Right-click in an editor -> Extensions -> CanaryAgent to insert markers. " +
                    "Tip: most tool-calling payloads travel backend -> LLM provider, not browser -> backend; proxy your " +
                    "backend's outbound LLM traffic through Burp to see tool calls (docs/DEVELOPMENT.md section 6).",
            )
            return runtime
        }

        private fun startPersistence(
            api: MontoyaApi, tracker: MarkerTracker, inventory: ToolInventory, log: CanaryLog, writesEnabled: () -> Boolean,
        ): PersistenceService? =
            try {
                val service = PersistenceService(
                    MontoyaNode(api.persistence().extensionData()), tracker, inventory, log, writesEnabled = writesEnabled,
                )
                val (markers, tools) = service.restore()
                service.attach()
                log.info("Persistence: restored $markers marker(s) and $tools tool(s) from the project; saving is governed live by the 'Do not persist' setting.")
                service
            } catch (t: RuntimeException) {
                log.info("Persistence: unavailable (${t.javaClass.simpleName}: ${t.message}). See the Errors tab.")
                log.error("Project persistence unavailable; markers and inventory will not survive a reload.", t)
                null
            }

        /** One line at load when Burp's streaming settings would hide SSE responses from the extension. */
        private fun warnIfStreamingHidesSse(api: MontoyaApi, log: CanaryLog) {
            try {
                val json = api.burpSuite().exportProjectOptionsAsJson("project_options.http")
                StreamingSettingsHint.analyze(json)?.takeIf { it.hidesSseResponses }?.let { log.info("WARNING: ${it.message()}") }
            } catch (_: RuntimeException) {
                // Older Burp or restricted options export: no hint, the README documents the limitation.
            }
        }
    }

    /**
     * Flushes persistence (and detaches it) first, then stops the scheduler and timers, and only then
     * empties the stores so the project data is not wiped. Burp releases the registrations itself.
     */
    fun dispose() {
        persistence?.dispose()
        poller?.dispose()
        observationExecutor.dispose()
        tab.dispose()
        tracker.clear()
        inventory.clear()
        traffic.clear()
        log.info("Unloaded.")
    }
}
