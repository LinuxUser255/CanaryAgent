package com.canaryagent.persistence

import com.canaryagent.inventory.ToolInventory
import com.canaryagent.markers.MarkerTracker
import com.canaryagent.model.MarkerHit
import com.canaryagent.model.ToolEntry
import com.canaryagent.util.CanaryLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps markers and the tool inventory in Burp's project persistence.
 *
 * [restore] reads the stored tree into the stores before handlers run. After [attach], store changes
 * queue the affected keys and a daemon scheduler writes them behind every [flushIntervalMs]; removals
 * and clears are applied on the same flush. [dispose] detaches first and then flushes, so the caller
 * can clear the in-memory stores afterwards without wiping the project data.
 *
 * [writesEnabled] is consulted at every flush, never cached: Burp hands out stale settings values during
 * `initialize`, so the opt-out is honoured live. Restore always runs; an opt-out stops saving, and Clear
 * removes what is stored.
 *
 * Layout: `<root>/canaryagent` { schema, markers/<token>/..., tools/<name>/... }.
 */
class PersistenceService(
    private val root: KeyValueNode,
    private val tracker: MarkerTracker,
    private val inventory: ToolInventory,
    private val log: CanaryLog,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_MS,
    private val scheduler: ScheduledExecutorService? = defaultScheduler(),
    private val writesEnabled: () -> Boolean = { true },
) {
    companion object {
        const val ROOT_KEY = "canaryagent"
        const val MARKERS_KEY = "markers"
        const val TOOLS_KEY = "tools"
        const val COLLABORATOR_KEY = "collaboratorSecretKey"
        const val SCHEMA = 1L
        const val DEFAULT_FLUSH_MS = 1000L

        private fun defaultScheduler(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "CanaryAgent-Persistence").apply { isDaemon = true } }

        /** Reads every stored marker and tool under [root] into [tracker] and [inventory]. Returns counts. */
        fun restoreInto(root: KeyValueNode, tracker: MarkerTracker, inventory: ToolInventory, log: CanaryLog? = null): Pair<Int, Int> {
            val base = root.child(ROOT_KEY) ?: return 0 to 0
            var markers = 0
            var tools = 0
            base.child(MARKERS_KEY)?.let { m ->
                for (key in m.childKeys()) {
                    val node = m.child(key) ?: continue
                    val hit = MarkerCodec.read(node)
                    if (hit == null) { log?.once("restore-marker-$key", "Skipped stored marker '$key': unreadable or unknown values."); continue }
                    tracker.restore(hit)
                    markers++
                }
            }
            base.child(TOOLS_KEY)?.let { t ->
                for (key in t.childKeys()) {
                    val node = t.child(key) ?: continue
                    val entry = ToolCodec.read(node)
                    if (entry == null) { log?.once("restore-tool-$key", "Skipped stored tool '$key': unreadable."); continue }
                    inventory.restore(entry)
                    tools++
                }
            }
            return markers to tools
        }
    }

    private val base: KeyValueNode = root.childOrCreate(ROOT_KEY).also { it.setLong("schema", SCHEMA) }
    private val dirtyMarkers = ConcurrentHashMap.newKeySet<String>()
    private val dirtyTools = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var clearMarkers = false
    @Volatile private var clearTools = false
    @Volatile private var pendingCollaboratorKey: String? = null
    @Volatile private var attached = false

    private val markerListener = object : MarkerTracker.Listener {
        override fun onMarkerChanged(token: String, hit: MarkerHit?) { dirtyMarkers += token.lowercase() }
        override fun onMarkersCleared() { clearMarkers = true; dirtyMarkers.clear() }
    }
    private val toolListener = object : ToolInventory.Listener {
        override fun onToolChanged(entry: ToolEntry, isNew: Boolean) { dirtyTools += entry.name.lowercase() }
        override fun onInventoryCleared() { clearTools = true; dirtyTools.clear() }
    }

    /** Loads stored state into the stores. Call before [attach] and before handlers are registered. */
    fun restore(): Pair<Int, Int> = restoreInto(root, tracker, inventory, log)

    /** Starts write-behind. */
    fun attach() {
        if (attached) return
        attached = true
        tracker.addListener(markerListener)
        inventory.addListener(toolListener)
        scheduler?.scheduleWithFixedDelay(::safeFlush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** True when the live opt-out setting allows writing. */
    fun isWriting(): Boolean = try { writesEnabled() } catch (_: RuntimeException) { true }

    /** The stored Collaborator client key, or null. Clear leaves it in place: the client stays valid. */
    fun loadCollaboratorKey(): String? = base.getString(COLLABORATOR_KEY)?.takeIf { it.isNotBlank() }

    /** Queues the Collaborator client key; written at the next flush like every other change. */
    fun saveCollaboratorKey(key: String) {
        pendingCollaboratorKey = key
    }

    /** Writes every queued change now, unless writes are opted out (then the queue is kept). Safe from any thread. */
    @Synchronized
    fun flushNow() {
        if (!isWriting()) return
        if (clearMarkers) { base.deleteChild(MARKERS_KEY); clearMarkers = false }
        if (clearTools) { base.deleteChild(TOOLS_KEY); clearTools = false }
        pendingCollaboratorKey?.let { base.setString(COLLABORATOR_KEY, it); pendingCollaboratorKey = null }

        val markerKeys = dirtyMarkers.toList(); dirtyMarkers.removeAll(markerKeys.toSet())
        if (markerKeys.isNotEmpty()) {
            val m = base.childOrCreate(MARKERS_KEY)
            for (key in markerKeys) {
                val hit = tracker[key]
                if (hit == null) m.deleteChild(key) else MarkerCodec.write(m.childOrCreate(key), hit)
            }
        }
        val toolKeys = dirtyTools.toList(); dirtyTools.removeAll(toolKeys.toSet())
        if (toolKeys.isNotEmpty()) {
            val t = base.childOrCreate(TOOLS_KEY)
            for (key in toolKeys) {
                val entry = inventory[key]
                if (entry == null) t.deleteChild(key) else ToolCodec.write(t.childOrCreate(key), entry)
            }
        }
    }

    /** Detaches from the stores, flushes, stops the scheduler. The stores may be cleared afterwards. */
    fun dispose() {
        if (attached) {
            tracker.removeListener(markerListener)
            inventory.removeListener(toolListener)
            attached = false
        }
        safeFlush()
        scheduler?.shutdownNow()
    }

    private fun safeFlush() {
        try {
            flushNow()
        } catch (t: Throwable) {
            log.once("persist-flush:${t.javaClass.simpleName}", "Persisting markers/inventory failed: ${t.message}")
        }
    }
}
