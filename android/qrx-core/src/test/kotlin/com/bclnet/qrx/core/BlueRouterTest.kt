package com.bclnet.qrx.core

import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.toJsonString
import com.bclnet.qrx.core.blue.BlueGlyphService
import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class BlueRouterTest {
    @Test
    fun routingAndParams() {
        val router = BlueRouter()
        router.get("/items/{id}") { _, params -> com.bclnet.qrx.core.blue.BlueResponse.ok("item ${params["id"]}") }
        router.post("/items/{id}") { request, params -> com.bclnet.qrx.core.blue.BlueResponse.ok("saved ${params["id"]}: ${request.body}") }
        assertEquals("item 42", router.handle(BlueRequest.get("/items/42")).content)
        assertEquals("saved 7: x", router.handle(BlueRequest("POST", "/items/7", body = "x")).content)
        assertEquals(404, router.handle(BlueRequest.get("/nothing")).statusCode)
        assertEquals(405, router.handle(BlueRequest("DELETE", "/items/1")).statusCode)
        assertEquals(400, router.handle("garbage").statusCode)
        assertEquals("item 9", router.handle("GET /items/9 BLUE/1.0\n\n").content)
    }

    @Test
    fun glyphServiceRoutes() {
        val service = BlueGlyphService()
        val router = service.router

        assertEquals("pong", router.handle(BlueRequest.get("/ping")).content)
        assertEquals("[]", router.handle(BlueRequest.get("/glyphs")).content)
        assertEquals(404, router.handle(BlueRequest.get("/glyph/menu")).statusCode)

        assertEquals(201, router.handle(BlueRequest.post("/glyph/menu", """{"_button":{},"text":"Menu"}""")).statusCode)
        assertEquals("""["menu"]""", router.handle(BlueRequest.get("/glyphs")).content)
        val fetched = router.handle(BlueRequest.get("/glyph/menu"))
        assertEquals(200, fetched.statusCode)
        assertEquals(GlyphContent.Button("Menu", null), GlyphDocument.parse(fetched.content!!).content)
        assertEquals(400, router.handle(BlueRequest.post("/glyph/bad", "nope")).statusCode)

        service.share(GlyphDocument.button("Shared"), "shared")
        assertEquals(listOf("menu", "shared"), service.names)
        service.unshare("menu")
        assertEquals(listOf("shared"), service.names)
    }
}
