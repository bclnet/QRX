package com.bclnet.qrx.core

import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphLookupTest {
    private class FakeFetcher : GlyphFetcher {
        val responses = mutableMapOf<String, ByteArray>()
        var calls = 0
        override fun fetch(url: String): ByteArray {
            calls++
            return responses[url] ?: throw GlyphLookupException.Http(404)
        }
    }

    private class FakeBlue : BlueTransport {
        val requests = mutableListOf<Pair<BlueRequest, String>>()
        var response = BlueResponse.json("""{"_button":{},"text":"From BLE"}""")
        override fun send(request: BlueRequest, device: String, callback: (Result<BlueResponse>) -> Unit) {
            requests.add(request to device)
            callback(Result.success(response))
        }
    }

    @Test
    fun inlineHttpAndCache() = runBlocking {
        val fetcher = FakeFetcher()
        fetcher.responses["https://x/glyph.json"] = """{"_image":{},"url":"https://x/y.png"}""".toByteArray()
        val lookup = GlyphLookup(fetcher)

        val inline = lookup.lookup(GlyphBarcode("size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}"))
        assertEquals(GlyphContent.Button("Inline", null), inline.content)
        assertEquals(0, fetcher.calls)

        val barcode = GlyphBarcode("https://x/glyph.json")
        assertEquals(GlyphContent.Image("https://x/y.png"), lookup.lookup(barcode).content)
        lookup.lookup(barcode)
        assertEquals("second lookup is served from the cache", 1, fetcher.calls)
        lookup.lookup(barcode, refresh = true)
        assertEquals(2, fetcher.calls)

        val failure = runCatching { lookup.lookup(GlyphBarcode("https://x/missing.json")) }.exceptionOrNull()
        assertEquals("HTTP 404", failure?.message)
        assertTrue(runCatching { lookup.lookup(GlyphBarcode.empty) }.exceptionOrNull() is GlyphLookupException.NoContent)
    }

    @Test
    fun bluetooth() = runBlocking {
        val blue = FakeBlue()
        val lookup = GlyphLookup(FakeFetcher(), blue)
        val document = lookup.lookup(GlyphBarcode("blue://Kiosk/glyph/menu"))
        assertEquals(GlyphContent.Button("From BLE", null), document.content)
        assertEquals(1, blue.requests.size)
        assertEquals("/glyph/menu", blue.requests[0].first.uri)
        assertEquals("Kiosk", blue.requests[0].second)

        blue.response = BlueResponse.error(404, "no glyph")
        val error = runCatching { lookup.lookup(GlyphBarcode("blue://*/glyph/x")) }.exceptionOrNull()
        assertEquals("BLUE 404: no glyph", error?.message)

        val unavailable = runCatching { GlyphLookup(FakeFetcher()).lookup(GlyphBarcode("blue://*/glyph/x")) }.exceptionOrNull()
        assertNotNull(unavailable)
        assertEquals("Bluetooth is not available for blue:// glyphs", unavailable?.message)
    }
}
