package com.canaryagent.ui

import burp.api.montoya.MontoyaApi
import com.canaryagent.annotation.AnnotatedTrafficStore
import com.canaryagent.inventory.ToolInventory
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.ToolEntry
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * The single CanaryAgent suite tab: Tool Inventory over Marker Hits over Annotated Traffic.
 * Store listeners (called on Burp threads) only poke a [RefreshCoalescer]; all table updates
 * happen on the EDT from immutable snapshots. Call [dispose] on unload.
 */
class CanaryAgentTab(
    api: MontoyaApi,
    private val tracker: MarkerTracker,
    private val inventory: ToolInventory,
    private val traffic: AnnotatedTrafficStore,
) {
    private val actions = MontoyaUiActions(api)
    private val trafficPanel = AnnotatedTrafficPanel(traffic, MontoyaMessageViewer(api), actions)
    private val inventoryPanel = ToolInventoryPanel(inventory, actions) { tool -> trafficPanel.setToolFilter(tool) }
    private val markersPanel = MarkerHitsPanel(tracker, actions)

    private val inventoryRefresh = RefreshCoalescer { inventoryPanel.reload() }
    private val markersRefresh = RefreshCoalescer { markersPanel.reload() }
    private val trafficRefresh = RefreshCoalescer { trafficPanel.reload() }

    private val inventoryListener = object : ToolInventory.Listener {
        override fun onToolChanged(entry: ToolEntry, isNew: Boolean) = inventoryRefresh.requestRefresh()
        override fun onInventoryCleared() = inventoryRefresh.requestRefresh()
    }
    private val markerListener = object : MarkerTracker.Listener {
        override fun onMarkerChanged(token: String, hit: MarkerHit?) = markersRefresh.requestRefresh()
        override fun onMarkersCleared() = markersRefresh.requestRefresh()
    }
    private val trafficListener = AnnotatedTrafficStore.Listener { trafficRefresh.requestRefresh() }

    val component: JComponent

    init {
        val lower = JSplitPane(JSplitPane.VERTICAL_SPLIT, markersPanel.panel, trafficPanel.component).apply {
            resizeWeight = 0.3
            isContinuousLayout = true
        }
        val root = JSplitPane(JSplitPane.VERTICAL_SPLIT, inventoryPanel.panel, lower).apply {
            resizeWeight = 0.25
            isContinuousLayout = true
        }
        component = JPanel(BorderLayout()).apply { add(root, BorderLayout.CENTER) }
        api.userInterface().applyThemeToComponent(component)

        inventory.addListener(inventoryListener)
        tracker.addListener(markerListener)
        traffic.addListener(trafficListener)

        inventoryRefresh.refreshNow()
        markersRefresh.refreshNow()
        trafficRefresh.refreshNow()
    }

    /** Stops timers and detaches from the stores. Safe to call more than once. */
    fun dispose() {
        inventory.removeListener(inventoryListener)
        tracker.removeListener(markerListener)
        traffic.removeListener(trafficListener)
        inventoryRefresh.stop()
        markersRefresh.stop()
        trafficRefresh.stop()
    }
}
