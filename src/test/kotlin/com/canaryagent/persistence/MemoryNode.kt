package com.canaryagent.persistence

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse

/** In-memory [KeyValueNode] with the same semantics as Burp's persisted object tree. */
class MemoryNode : KeyValueNode {
    val strings = LinkedHashMap<String, String>()
    val longs = LinkedHashMap<String, Long>()
    val requests = LinkedHashMap<String, HttpRequest>()
    val responses = LinkedHashMap<String, HttpResponse>()
    val children = LinkedHashMap<String, MemoryNode>()

    override fun getString(key: String): String? = strings[key]
    override fun setString(key: String, value: String) { strings[key] = value }
    override fun deleteString(key: String) { strings.remove(key) }

    override fun getLong(key: String): Long? = longs[key]
    override fun setLong(key: String, value: Long) { longs[key] = value }

    override fun getRequest(key: String): HttpRequest? = requests[key]
    override fun setRequest(key: String, value: HttpRequest) { requests[key] = value }
    override fun deleteRequest(key: String) { requests.remove(key) }

    override fun getResponse(key: String): HttpResponse? = responses[key]
    override fun setResponse(key: String, value: HttpResponse) { responses[key] = value }
    override fun deleteResponse(key: String) { responses.remove(key) }

    override fun child(key: String): KeyValueNode? = children[key]
    override fun childOrCreate(key: String): KeyValueNode = children.getOrPut(key) { MemoryNode() }
    override fun childKeys(): Set<String> = children.keys.toSet()
    override fun deleteChild(key: String) { children.remove(key) }
}
