package com.canaryagent.collaborator

import burp.api.montoya.collaborator.HttpDetails
import burp.api.montoya.http.HttpProtocol
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import java.lang.reflect.Proxy

/**
 * Montoya's request factories need a running Burp, so the Collaborator tests build the request a
 * captured interaction carries through a reflective proxy that answers only what the correlator reads:
 * the path, header lookups, the start line for the excerpt, and `copyToTempFile` (returns itself).
 */
object FakeHttp {
    fun request(method: String, path: String, headers: Map<String, String>): HttpRequest {
        val lower = headers.mapKeys { it.key.lowercase() }
        val startLine = "$method $path HTTP/1.1"
        return Proxy.newProxyInstance(HttpRequest::class.java.classLoader, arrayOf(HttpRequest::class.java)) { self, m, args ->
            when (m.name) {
                "method" -> method
                "path" -> path
                "pathWithoutQuery" -> path.substringBefore('?')
                "url" -> "http://collab.test$path"
                "headerValue" -> lower[(args!![0] as String).lowercase()]
                "hasHeader" -> if (args!!.size == 1 && args[0] is String) lower.containsKey((args[0] as String).lowercase()) else false
                "copyToTempFile" -> self
                "toString" -> startLine + "\r\n" + headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n\r\n"
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                else -> throw UnsupportedOperationException("FakeHttp.request does not implement ${m.name}")
            }
        } as HttpRequest
    }

    fun details(request: HttpRequest): HttpDetails {
        val rr = Proxy.newProxyInstance(HttpRequestResponse::class.java.classLoader, arrayOf(HttpRequestResponse::class.java)) { self, m, args ->
            when (m.name) {
                "request" -> request
                "response" -> null
                "copyToTempFile" -> self
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                "toString" -> "FakeHttpRequestResponse"
                else -> throw UnsupportedOperationException("FakeHttp.details does not implement ${m.name}")
            }
        } as HttpRequestResponse
        return object : HttpDetails {
            override fun protocol(): HttpProtocol = HttpProtocol.HTTP
            override fun requestResponse(): HttpRequestResponse = rr
        }
    }
}
