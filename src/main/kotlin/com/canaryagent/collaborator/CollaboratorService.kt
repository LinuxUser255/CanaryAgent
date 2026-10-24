package com.canaryagent.collaborator

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.CollaboratorClient
import burp.api.montoya.collaborator.CollaboratorPayload
import burp.api.montoya.collaborator.Interaction
import burp.api.montoya.collaborator.SecretKey
import com.canaryagent.util.CanaryLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Collaborator client (Professional only), restored from a stored secret key when one exists and
 * created lazily otherwise. The key of a newly created client is handed to [saveKey] so the next load
 * can restore the same client and still fetch interactions for payloads placed before a reload.
 * Creation can fail when Collaborator is disabled in Burp's settings; after one failure the service
 * reports itself unavailable and the context-menu actions disappear.
 * Only constructed when [com.canaryagent.util.Capabilities] allows it.
 */
class CollaboratorService(
    private val api: MontoyaApi,
    private val log: CanaryLog,
    private val loadKey: () -> String? = { null },
    private val saveKey: (String) -> Unit = {},
) {
    /** How the current client came to exist. */
    enum class Origin { NONE, RESTORED, CREATED }

    private val client = AtomicReference<CollaboratorClient?>(null)
    private val failed = AtomicBoolean(false)

    @Volatile
    var origin: Origin = Origin.NONE
        private set

    /** Optimistic until the first failed client creation. */
    fun isAvailable(): Boolean = !failed.get()

    /** A fresh payload, e.g. `abc123.oastify.com`, or null when Collaborator is unavailable. */
    fun newPayload(): CollaboratorPayload? = client()?.generatePayload()

    /** Interactions not yet fetched; empty until a client exists. */
    fun fetchInteractions(): List<Interaction> = client.get()?.getAllInteractions() ?: emptyList()

    /** The current client's encoded secret key, or null when no client exists yet. */
    fun secretKey(): String? = client.get()?.secretKey?.toString()

    /**
     * Restores the client at load when a key is stored, so the poller fetches interactions for payloads
     * placed before the reload. Without a stored key nothing happens: the client is created on first use.
     */
    fun restoreIfStored(): Origin {
        val stored = storedKey() ?: return Origin.NONE
        if (stored.isEmpty()) return Origin.NONE
        client()
        return origin
    }

    @Synchronized
    private fun client(): CollaboratorClient? {
        client.get()?.let { return it }
        if (failed.get()) return null
        return try {
            val (created, how) = ClientBootstrap.resolve(
                storedKey(),
                restore = { key -> api.collaborator().restoreClient(SecretKey.secretKey(key)) },
                create = { api.collaborator().createClient() },
                onRestoreFailed = { t -> log.info("Collaborator: stored client key could not be restored (${t.message}); creating a new client.") },
            )
            client.set(created)
            origin = how
            if (how == Origin.CREATED) {
                try { saveKey(created.secretKey.toString()) } catch (_: RuntimeException) { /* persistence unavailable */ }
            }
            created
        } catch (t: RuntimeException) {
            failed.set(true)
            log.once("collab-unavailable", "Collaborator is unavailable (${t.message}). Collaborator actions are disabled.")
            null
        }
    }

    private fun storedKey(): String? = try { loadKey()?.trim() } catch (_: RuntimeException) { null }
}

/** Pure restore-or-create decision, generic so it is unit-tested without a Burp client. */
object ClientBootstrap {
    /**
     * Restores a client from [storedKey] when one is present and Burp accepts it, otherwise creates a
     * new one; the returned origin says which happened. [onRestoreFailed] sees a rejected key before
     * the fallback runs.
     */
    fun <T> resolve(
        storedKey: String?,
        restore: (String) -> T,
        create: () -> T,
        onRestoreFailed: (RuntimeException) -> Unit = {},
    ): Pair<T, CollaboratorService.Origin> {
        val key = storedKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null) {
            try {
                return restore(key) to CollaboratorService.Origin.RESTORED
            } catch (t: RuntimeException) {
                onRestoreFailed(t)
            }
        }
        return create() to CollaboratorService.Origin.CREATED
    }
}
