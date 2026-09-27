/*
 * BlueRouter.kt
 * QRX
 *
 * Dispatches BLUE requests to handlers by method and path with `{param}`
 * segments (mirror of BlueRouter.swift). The QRX server routes
 * (docs/BLUE.md) are installed by BlueGlyphService.
 */
package com.bclnet.qrx.core.blue

import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.parseJson
import com.bclnet.jsonui.toJsonString
import com.bclnet.qrx.core.GlyphDocument
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

typealias BlueHandler = (request: BlueRequest, params: Map<String, String>) -> BlueResponse

class BlueRouter {
    private class Route(val method: String, val segments: List<String>, val handler: BlueHandler)

    private val routes = CopyOnWriteArrayList<Route>()

    fun on(method: String, path: String, handler: BlueHandler) { routes.add(Route(method.uppercase(), segments(path), handler)) }
    fun get(path: String, handler: BlueHandler) = on("GET", path, handler)
    fun post(path: String, handler: BlueHandler) = on("POST", path, handler)

    /** Routes `request`; 404 when no path matches, 405 when the path matches another method. */
    fun handle(request: BlueRequest): BlueResponse {
        val segments = segments(request.path)
        var pathMatched = false
        for (route in routes) {
            val params = match(route.segments, segments) ?: continue
            pathMatched = true
            if (route.method != request.method.uppercase()) continue
            return route.handler(request, params)
        }
        return if (pathMatched) BlueResponse.error(405) else BlueResponse.error(404, "no route for ${request.path}")
    }

    /** Parses request text and routes it; malformed requests get a 400. */
    fun handle(text: String): BlueResponse = try {
        handle(BlueRequest.parse(text))
    } catch (e: BlueProtocolException) {
        BlueResponse.error(400, e.message)
    }

    companion object {
        fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }.map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

        fun match(pattern: List<String>, segments: List<String>): Map<String, String>? {
            if (pattern.size != segments.size) return null
            val params = mutableMapOf<String, String>()
            for ((p, s) in pattern.zip(segments)) {
                if (p.startsWith("{") && p.endsWith("}")) params[p.substring(1, p.length - 1)] = s
                else if (p != s) return null
            }
            return params
        }
    }
}

/** Stores the glyph documents a device shares and serves the QRX routes. */
class BlueGlyphService(initial: Map<String, GlyphDocument> = emptyMap()) {
    val router = BlueRouter()
    private val documents = ConcurrentHashMap<String, GlyphDocument>(initial)

    /** Reads the LED colour last written; null when no board is connected. */
    var ledColor: () -> LedColor? = { null }
    /** Writes a colour to the board; false when no board is connected. */
    var setLedColor: (LedColor) -> Boolean = { false }
    /** The board's battery level 0–100, null when unknown. */
    var batteryLevel: () -> Int? = { null }

    init { install() }

    fun share(document: GlyphDocument, name: String) { documents[name] = document }
    fun unshare(name: String) { documents.remove(name) }
    val names: List<String> get() = documents.keys.sorted()
    fun document(name: String): GlyphDocument? = documents[name]

    private fun install() {
        router.get("/ping") { _, _ -> BlueResponse.ok("pong") }
        router.get("/glyphs") { _, _ -> BlueResponse.json(jsonArrayOf(*names.toTypedArray()).toJsonString()) }
        router.get("/glyph/{name}") { _, params ->
            val name = params["name"] ?: ""
            document(name)?.let { BlueResponse.json(it.toJsonString()) } ?: BlueResponse.error(404, "no glyph named $name")
        }
        router.post("/glyph/{name}") { request, params ->
            val name = params["name"] ?: return@post BlueResponse.error(400, "missing name")
            val body = request.body ?: return@post BlueResponse.error(400, "missing body")
            try {
                share(GlyphDocument.parse(body), name)
                BlueResponse(201)
            } catch (e: Exception) {
                BlueResponse.error(400, e.message)
            }
        }
        router.get("/led") { _, _ ->
            ledColor()?.let { BlueResponse.json(it.json.toJsonString()) } ?: BlueResponse.error(503, "no LED board connected")
        }
        router.post("/led") { request, _ ->
            val color = request.body?.let { runCatching { LedColor.fromJson(parseJson(it)) }.getOrNull() }
                ?: return@post BlueResponse.error(400, "expected {\"r\":0-255,\"g\":0-255,\"b\":0-255}")
            if (setLedColor(color)) BlueResponse.json(color.json.toJsonString()) else BlueResponse.error(503, "no LED board connected")
        }
        router.get("/battery") { _, _ ->
            batteryLevel()?.let { BlueResponse.json(jsonObjectOf("level" to it).toJsonString()) } ?: BlueResponse.error(503, "no LED board connected")
        }
    }
}
