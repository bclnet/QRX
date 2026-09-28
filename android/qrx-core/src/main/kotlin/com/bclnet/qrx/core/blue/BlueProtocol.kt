/*
 * BlueProtocol.kt
 * QRX
 *
 * BLUE/1.0: an HTTP-like text protocol carried over Bluetooth LE (mirror of
 * BlueProtocol.swift, see docs/BLUE.md).
 */
package com.bclnet.qrx.core.blue

import java.net.URLDecoder

class BlueProtocolException(message: String) : IllegalArgumentException(message)

object Blue {
    const val VERSION = "BLUE/1.0"
    const val CONTENT_LENGTH = "Content-Length"
    const val CONTENT_TYPE = "Content-Type"
    const val JSON = "application/json"
    const val TEXT = "text/plain"
}

/** Splits a message into header lines and body, the `LineParser` of the reference app. */
object BlueLineParser {
    data class Message(val lines: List<String>, val body: String?)

    /** Header lines end at the first empty line; the rest is the body (honouring `Content-Length`). */
    fun parse(text: String): Message {
        val normalized = text.replace("\r\n", "\n")
        val lines = mutableListOf<String>()
        var body: String? = null
        var index = 0
        while (index < normalized.length) {
            val lineEnd = normalized.indexOf('\n', index).let { if (it < 0) normalized.length else it }
            val line = normalized.substring(index, lineEnd)
            index = if (lineEnd < normalized.length) lineEnd + 1 else lineEnd
            if (line.isEmpty()) {
                if (index < normalized.length) body = normalized.substring(index)
                break
            }
            lines.add(line)
        }
        val declared = lines.mapNotNull { header(it) }.firstOrNull { it.first.equals(Blue.CONTENT_LENGTH, ignoreCase = true) }?.second?.toIntOrNull()
        if (declared != null && body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (bytes.size > declared) body = String(bytes, 0, declared, Charsets.UTF_8)
        }
        return Message(lines, body)
    }

    fun header(line: String): Pair<String, String>? {
        val separator = line.indexOf(':')
        if (separator < 0) return null
        val name = line.substring(0, separator).trim()
        if (name.isEmpty()) return null
        return name to line.substring(separator + 1).trim()
    }

    fun headers(lines: List<String>): Map<String, String> = lines.associate { line ->
        header(line) ?: throw BlueProtocolException("BLUE: malformed header \"$line\"")
    }
}

data class BlueRequest(
    val method: String = "GET",
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    /** The path part of the URI, without query. */
    val path: String get() = uri.substringBefore('?')

    val query: Map<String, String>
        get() {
            if (!uri.contains('?')) return emptyMap()
            return uri.substringAfter('?').split('&').filter { it.isNotEmpty() }.associate { pair ->
                val kv = pair.split('=', limit = 2).map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                kv[0] to (kv.getOrNull(1) ?: "")
            }
        }

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Serializes the request; `Content-Length` is added when there is a body. */
    val text: String
        get() {
            val all = headers.toMutableMap()
            body?.let { all[Blue.CONTENT_LENGTH] = it.toByteArray(Charsets.UTF_8).size.toString() }
            val lines = mutableListOf("${method.uppercase()} $uri ${Blue.VERSION}")
            all.keys.sorted().forEach { lines.add("$it: ${all[it]}") }
            lines.add("")
            return lines.joinToString("\n") + "\n" + (body ?: "")
        }

    companion object {
        fun get(uri: String) = BlueRequest("GET", uri)
        fun post(uri: String, json: String) = BlueRequest("POST", uri, mapOf(Blue.CONTENT_TYPE to Blue.JSON), json)

        /** Parses `METHOD uri BLUE/1.0`, headers and body. */
        fun parse(text: String): BlueRequest {
            val message = BlueLineParser.parse(text)
            val first = message.lines.firstOrNull() ?: throw BlueProtocolException("BLUE: empty message")
            val parts = first.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) throw BlueProtocolException("BLUE: malformed request line \"$first\"")
            val hasVersion = parts.size >= 3 && parts.last().uppercase().startsWith("BLUE/")
            if (hasVersion && parts.last().uppercase() != Blue.VERSION) throw BlueProtocolException("BLUE: unsupported version ${parts.last()}")
            val uri = (if (hasVersion) parts.subList(1, parts.size - 1) else parts.drop(1)).joinToString(" ")
            return BlueRequest(parts[0].uppercase(), uri, BlueLineParser.headers(message.lines.drop(1)), message.body)
        }
    }
}

data class BlueResponse(
    val statusCode: Int = 200,
    val statusDescription: String = reason(statusCode),
    val headers: Map<String, String> = emptyMap(),
    val content: String? = null,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Serializes the response; the reference app called this `finish()`. */
    val text: String
        get() {
            val all = headers.toMutableMap()
            content?.let { all[Blue.CONTENT_LENGTH] = it.toByteArray(Charsets.UTF_8).size.toString() }
            val lines = mutableListOf("${Blue.VERSION} $statusCode $statusDescription")
            all.keys.sorted().forEach { lines.add("$it: ${all[it]}") }
            lines.add("")
            return lines.joinToString("\n") + "\n" + (content ?: "")
        }

    fun finish(): String = text

    companion object {
        fun ok(content: String, type: String = Blue.TEXT) = BlueResponse(200, headers = mapOf(Blue.CONTENT_TYPE to type), content = content)
        fun json(json: String, statusCode: Int = 200) = BlueResponse(statusCode, headers = mapOf(Blue.CONTENT_TYPE to Blue.JSON), content = json)
        fun error(statusCode: Int, message: String? = null) = BlueResponse(statusCode, headers = mapOf(Blue.CONTENT_TYPE to Blue.TEXT), content = message ?: reason(statusCode))

        fun reason(status: Int): String = when (status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"
            else -> ""
        }

        /** Parses `BLUE/1.0 status description`, headers and content. */
        fun parse(text: String): BlueResponse {
            val message = BlueLineParser.parse(text)
            val first = message.lines.firstOrNull() ?: throw BlueProtocolException("BLUE: empty message")
            val parts = first.split(' ', limit = 3).filter { it.isNotEmpty() }
            val status = parts.getOrNull(1)?.toIntOrNull() ?: throw BlueProtocolException("BLUE: malformed status line \"$first\"")
            if (parts[0].uppercase() != Blue.VERSION) throw BlueProtocolException("BLUE: unsupported version ${parts[0]}")
            return BlueResponse(status, parts.getOrNull(2) ?: reason(status), BlueLineParser.headers(message.lines.drop(1)), message.body)
        }
    }
}
