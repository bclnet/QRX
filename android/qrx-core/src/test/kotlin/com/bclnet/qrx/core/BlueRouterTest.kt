package com.bclnet.qrx.core

import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.toJsonString
import com.bclnet.qrx.core.blue.BlueGlyphService
import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueRouter
import com.bclnet.qrx.core.blue.LedColor
import com.bclnet.qrx.core.blue.ParticleUUIDs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        var written: LedColor? = null
        service.setLedColor = { written = it; true }
        service.ledColor = { written }
        service.batteryLevel = { 87 }
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

        assertEquals(503, router.handle(BlueRequest.get("/led")).statusCode)
        assertEquals("""{"b":0,"g":10,"r":255}""", router.handle(BlueRequest.post("/led", """{"r":255,"g":10,"b":0}""")).content)
        assertEquals(LedColor(255, 10, 0), written)
        assertEquals("""{"b":0,"g":10,"r":255}""", router.handle(BlueRequest.get("/led")).content)
        assertEquals(400, router.handle(BlueRequest.post("/led", """{"r":1}""")).statusCode)
        assertEquals("""{"level":87}""", router.handle(BlueRequest.get("/battery")).content)

        service.share(GlyphDocument.button("Shared"), "shared")
        assertEquals(listOf("menu", "shared"), service.names)
        service.unshare("menu")
        assertEquals(listOf("shared"), service.names)
    }

    @Test
    fun ledColor() {
        assertEquals(LedColor(255, 0, 13), LedColor.fromJson(jsonObjectOf("r" to 300, "g" to -5, "b" to 12.6)))
        assertEquals(LedColor(1, 2, 3), LedColor.fromJson(jsonObjectOf("red" to 1, "green" to 2, "blue" to 3)))
        assertNull(LedColor.fromJson(jsonObjectOf("r" to 1)))
        val color = LedColor(9, 8, 7)
        assertEquals(9.toByte(), color.bytes(LedColor.Channel.Red)[0])
        assertEquals(7.toByte(), color.bytes(LedColor.Channel.Blue)[0])
        assertEquals(ParticleUUIDs.GREEN_LED, LedColor.Channel.Green.characteristic)
        assertEquals("""{"b":7,"g":8,"r":9}""", color.json.toJsonString())
        assertThrows(IllegalArgumentException::class.java) { LedColor(256, 0, 0) }
    }
}
