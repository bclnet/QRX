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

import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueResponse
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

sealed class GlyphLookupException(message: String) : Exception(message) {
    class NoContent : GlyphLookupException("the code has no URL and no inline document")
    class BluetoothUnavailable : GlyphLookupException("Bluetooth is not available for blue:// glyphs")
    class Http(val status: Int) : GlyphLookupException("HTTP $status")
    class Blue(val status: Int, val detail: String?) : GlyphLookupException("BLUE $status${detail?.let { ": $it" } ?: ""}")
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

    /**
     * Resolves `barcode`. Network and Bluetooth work happens where the fetcher/transport
     * run: HTTP fetches use the calling thread, so call from a background thread or use
     * the suspending overload.
     */
    fun lookup(barcode: GlyphBarcode, refresh: Boolean = false, callback: (Result<GlyphDocument>) -> Unit) {
        if (!refresh) cache[barcode.id]?.let { callback(Result.success(it)); return }
        val finish: (Result<GlyphDocument>) -> Unit = { result ->
            result.getOrNull()?.let { cache[barcode.id] = it }
            callback(result)
        }
        barcode.inlineDocument?.let { inline ->
            finish(runCatching { GlyphDocument.parse(inline) })
            return
        }
        barcode.blueTarget?.let { target ->
            val transport = blue ?: run { finish(Result.failure(GlyphLookupException.BluetoothUnavailable())); return }
            transport.send(BlueRequest.get(target.path), target.device) { result ->
                finish(result.mapCatching { response ->
                    if (!response.isSuccess) throw GlyphLookupException.Blue(response.statusCode, response.content)
                    GlyphDocument.parse(response.content ?: "")
                })
            }
            return
        }
        val url = barcode.location ?: run { finish(Result.failure(GlyphLookupException.NoContent())); return }
        finish(runCatching { GlyphDocument.parse(String(fetcher.fetch(url), Charsets.UTF_8)) })
    }

    suspend fun lookup(barcode: GlyphBarcode, refresh: Boolean = false): GlyphDocument =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            lookup(barcode, refresh) { result -> continuation.resumeWith(result) }
        }

    fun cached(id: String): GlyphDocument? = cache[id]

    fun clearCache() = cache.clear()
}
