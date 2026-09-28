/*
 * GlyphBarcode.kt
 * QRX
 *
 * The parsed payload of a QR code (mirror of GlyphBarcode.swift, see docs/GLYPH.md).
 */
package com.bclnet.qrx.core

import java.net.URI
import java.net.URLDecoder

class GlyphBarcode(val payload: String) {
    /** Headers other than `size:` and the URL, lower-cased names. Flag lines are stored as `name: "1"`. */
    val headers: Map<String, String>
    val sizes: Map<GlyphSelector, GlyphSize>
    /** The `http(s)://` or `blue://` line of the payload, as written. */
    val location: String?
    /** Whether the same code may be shown several times. */
    val multi: Boolean
    /** Text after the first blank line; the inline document when there is no location. */
    val body: String?

    init {
        val lines = payload.split("\n")
        val headers = linkedMapOf<String, String>()
        val sizes = linkedMapOf<GlyphSelector, GlyphSize>()
        var body: String? = null
        var sawHeader = false
        var plainText = false
        loop@ for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (!sawHeader) continue
                body = lines.drop(index + 1).joinToString("\n").trim().ifEmpty { null }
                break
            }
            sawHeader = true
            if (isURL(line)) { headers["url"] = line; continue }
            val separator = line.indexOf(':')
            if (separator < 0) {
                // Flags are single tokens; anything else means the payload is plain text, not a glyph.
                if (!isToken(line)) { plainText = true; break@loop }
                headers[line.lowercase()] = "1"
                continue
            }
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (name == "size") {
                val size = GlyphSize.parse(value)
                if (size != null) { sizes[size.selector] = size; continue }
            }
            headers[name] = value
        }
        if (plainText) { headers.clear(); sizes.clear(); body = payload.trim() }
        if (headers.isEmpty() && sizes.isEmpty() && body == null && payload.isNotBlank()) body = payload.trim()
        this.headers = headers
        this.sizes = sizes
        this.location = headers["url"]
        this.multi = headers.containsKey("multi")
        this.body = body
    }

    /** `location` as a URI, when it is one. */
    val url: URI? get() = location?.let { runCatching { URI(it) }.getOrNull() }

    /** Whether the document is fetched over Bluetooth (`blue://device/path`). */
    val isBluetooth: Boolean get() = location?.lowercase()?.startsWith("blue://") == true

    /** The peripheral name and path of a `blue://` location. Device names may contain spaces. */
    val blueTarget: BlueTarget?
        get() {
            val loc = location?.takeIf { isBluetooth } ?: return null
            val rest = loc.substring("blue://".length)
            val slash = rest.indexOf('/')
            val device = (if (slash < 0) rest else rest.substring(0, slash)).let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val path = if (slash < 0) "/" else rest.substring(slash)
            return BlueTarget(device.ifEmpty { "*" }, path)
        }

    /** The inline document body when the payload has no location. */
    val inlineDocument: String? get() = if (location == null) body else null

    /** The size to use for `selector`. */
    fun size(selector: GlyphSelector = GlyphSelector.Normal): GlyphSize = sizes.sizeFor(selector)

    /** A stable identity for the code (its payload). */
    val id: String get() = payload

    override fun equals(other: Any?): Boolean = other is GlyphBarcode && other.payload == payload
    override fun hashCode(): Int = payload.hashCode()
    override fun toString(): String = "GlyphBarcode(location=$location, sizes=$sizes, headers=$headers, body=$body)"

    data class BlueTarget(val device: String, val path: String)

    companion object {
        val empty = GlyphBarcode("")

        fun isURL(line: String): Boolean {
            val lower = line.lowercase()
            return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("blue://")
        }

        internal fun isToken(line: String): Boolean = line.isNotEmpty() && line.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }
}
