package com.canaryagent.persistence

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.persistence.PersistedObject

/**
 * The slice of Burp's `PersistedObject` the extension uses, as a seam so codecs and the persistence
 * service are unit-tested against an in-memory implementation.
 */
interface KeyValueNode {
    /** String slot [key], or null when unset. */
    fun getString(key: String): String?
    fun setString(key: String, value: String)
    fun deleteString(key: String)

    /** Long slot [key], or null when unset. */
    fun getLong(key: String): Long?
    fun setLong(key: String, value: Long)

    /** Request slot [key]; Burp keeps the copy in its temporary-file store, not on the heap. */
    fun getRequest(key: String): HttpRequest?
    fun setRequest(key: String, value: HttpRequest)
    fun deleteRequest(key: String)

    /** Response slot [key]; stored like requests. */
    fun getResponse(key: String): HttpResponse?
    fun setResponse(key: String, value: HttpResponse)
    fun deleteResponse(key: String)

    /** Existing child, or null. */
    fun child(key: String): KeyValueNode?

    /** Existing child, or a new empty one attached under [key]. */
    fun childOrCreate(key: String): KeyValueNode

    /** Names of the child nodes under this one. */
    fun childKeys(): Set<String>

    /** Removes the child [key] and everything beneath it. */
    fun deleteChild(key: String)
}

/** Adapter over Burp's project-scoped persistence. */
class MontoyaNode(private val obj: PersistedObject) : KeyValueNode {
    override fun getString(key: String): String? = obj.getString(key)
    override fun setString(key: String, value: String) = obj.setString(key, value)
    override fun deleteString(key: String) = obj.deleteString(key)

    override fun getLong(key: String): Long? = obj.getLong(key)
    override fun setLong(key: String, value: Long) = obj.setLong(key, value)

    override fun getRequest(key: String): HttpRequest? = obj.getHttpRequest(key)
    override fun setRequest(key: String, value: HttpRequest) = obj.setHttpRequest(key, value)
    override fun deleteRequest(key: String) = obj.deleteHttpRequest(key)

    override fun getResponse(key: String): HttpResponse? = obj.getHttpResponse(key)
    override fun setResponse(key: String, value: HttpResponse) = obj.setHttpResponse(key, value)
    override fun deleteResponse(key: String) = obj.deleteHttpResponse(key)

    override fun child(key: String): KeyValueNode? = obj.getChildObject(key)?.let(::MontoyaNode)

    override fun childOrCreate(key: String): KeyValueNode {
        obj.getChildObject(key)?.let { return MontoyaNode(it) }
        val created = PersistedObject.persistedObject()
        obj.setChildObject(key, created)
        // Re-read so writes go to the object Burp now owns, not a detached copy.
        return MontoyaNode(obj.getChildObject(key) ?: created)
    }

    override fun childKeys(): Set<String> = obj.childObjectKeys()
    override fun deleteChild(key: String) = obj.deleteChildObject(key)
}
