/*
 * GlyphLookup.kt
 * QRX
 *
 * Resolves a glyph payload into a document (mirror of GlyphLookup.swift):
 * inline bodies need no fetch, `http(s)://` goes through a GlyphFetcher
 * (HttpURLConnection by default), `blue://` through a BlueTransport supplied
 * by the platform's Bluetooth client.
 */
package com.bclnet.qrx.core

import com.bclnet.jsonui.JsonFragments
import com.bclnet.jsonui.hasFragmentReferences
import com.bclnet.jsonui.parseJson
import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueResponse
import kotlinx.serialization.json.JsonElement
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

sealed class GlyphLookupException(message: String) : Exception(message) {
    class NoContent : GlyphLookupException("the code has no URL and no inline document")
    class BluetoothUnavailable : GlyphLookupException("Bluetooth is not available for blue:// glyphs")
    class Http(val status: Int) : GlyphLookupException("HTTP $status")
    class Blue(val status: Int, val detail: String?) : GlyphLookupException("BLUE $status${detail?.let { ": $it" } ?: ""}")
    class Fragments(val detail: String) : GlyphLookupException("fragments: $detail")
    class TooManyFragments : GlyphLookupException("too many fragment documents")
}

/** Fetches bytes for an `http(s)://` URL. Called off the main thread. */
fun interface GlyphFetcher {
    @Throws(Exception::class)
    fun fetch(url: String): ByteArray
}

/** Sends a BLUE request to a named peripheral (`*` for any) and returns its response. */
fun interface BlueTransport {
    fun send(request: BlueRequest, device: String, callback: (Result<BlueResponse>) -> Unit)
}

class HttpFetcher(private val timeoutMs: Int = 15_000) : GlyphFetcher {
    override fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw GlyphLookupException.Http(status)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

class GlyphLookup(
    private val fetcher: GlyphFetcher = HttpFetcher(),
    var blue: BlueTransport? = null,
) {
    private val cache = ConcurrentHashMap<String, GlyphDocument>()
    /** How many fragment documents one glyph may pull in. */
    var maxFragmentDocuments = 16

    /**
     * Resolves `barcode`. Network and Bluetooth work happens where the fetcher/transport
     * run: HTTP fetches use the calling thread, so call from a background thread or use
     * the suspending overload. JSON fragments (`$ref`) in the document are fetched the same
     * way the document was and resolved against its URL before it is parsed.
     */
    fun lookup(barcode: GlyphBarcode, refresh: Boolean = false, callback: (Result<GlyphDocument>) -> Unit) {
        if (!refresh) cache[barcode.id]?.let { callback(Result.success(it)); return }
        val finish: (Result<GlyphDocument>) -> Unit = { result ->
            result.getOrNull()?.let { cache[barcode.id] = it }
            callback(result)
        }
        barcode.inlineDocument?.let { inline ->
            resolve(Result.success(inline), null, finish)
            return
        }
        barcode.blueTarget?.let { target ->
            val transport = blue ?: run { finish(Result.failure(GlyphLookupException.BluetoothUnavailable())); return }
            val base = runCatching { URI("blue://${target.device}${if (target.path.startsWith("/")) "" else "/"}${target.path}") }.getOrNull()
            fetchBlue(target.path, target.device, transport) { resolve(it, base, finish) }
            return
        }
        val url = barcode.location ?: run { finish(Result.failure(GlyphLookupException.NoContent())); return }
        resolve(runCatching { String(fetcher.fetch(url), Charsets.UTF_8) }, runCatching { URI(url) }.getOrNull(), finish)
    }

    // MARK: - Fragments

    private fun fetchBlue(path: String, device: String, transport: BlueTransport, callback: (Result<String>) -> Unit) {
        transport.send(BlueRequest.get(path), device) { result ->
            callback(result.mapCatching { response ->
                if (!response.isSuccess) throw GlyphLookupException.Blue(response.statusCode, response.content)
                response.content ?: ""
            })
        }
    }

    /** Fetches one fragment document: `http(s)` through the fetcher, `blue://device/path` through the transport. */
    private fun fetchDocument(url: URI, callback: (Result<JsonElement>) -> Unit) {
        val parse: (Result<String>) -> Unit = { result -> callback(result.mapCatching { parseJson(it) }) }
        if (url.scheme == "blue") {
            val transport = blue ?: run { callback(Result.failure(GlyphLookupException.BluetoothUnavailable())); return }
            fetchBlue(url.path ?: "/", url.host ?: "*", transport, parse)
        } else {
            parse(runCatching { String(fetcher.fetch(url.toString()), Charsets.UTF_8) })
        }
    }

    private fun resolve(json: Result<String>, base: URI?, callback: (Result<GlyphDocument>) -> Unit) {
        val value = json.mapCatching { parseJson(it) }.getOrElse { callback(Result.failure(it)); return }
        if (!value.hasFragmentReferences) { callback(runCatching { GlyphDocument.fromValue(value) }); return }
        resolve(value, base, JsonFragments(), 0, callback)
    }

    private fun resolve(value: JsonElement, base: URI?, resolver: JsonFragments, fetched: Int, callback: (Result<GlyphDocument>) -> Unit) {
        val missing = resolver.externalReferences(value, base)
        if (missing.isEmpty()) { callback(runCatching { GlyphDocument.fromValue(resolver.resolve(value, base)) }); return }
        if (fetched + missing.size > maxFragmentDocuments) { callback(Result.failure(GlyphLookupException.TooManyFragments())); return }
        var pending = missing.size
        var failure: Throwable? = null
        val lock = Any()
        for (url in missing) {
            fetchDocument(url) { result ->
                val done: Boolean
                synchronized(lock) {
                    result.fold(onSuccess = { resolver.register(it, url) }, onFailure = { if (failure == null) failure = GlyphLookupException.Fragments("$url: ${it.message}") })
                    pending -= 1
                    done = pending == 0
                }
                if (done) {
                    val error = failure
                    if (error != null) callback(Result.failure(error)) else resolve(value, base, resolver, fetched + missing.size, callback)
                }
            }
        }
    }

    suspend fun lookup(barcode: GlyphBarcode, refresh: Boolean = false): GlyphDocument =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            lookup(barcode, refresh) { result -> continuation.resumeWith(result) }
        }

    fun cached(id: String): GlyphDocument? = cache[id]

    fun clearCache() = cache.clear()
}
