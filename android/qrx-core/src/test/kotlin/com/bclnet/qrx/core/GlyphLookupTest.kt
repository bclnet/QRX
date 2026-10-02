package com.bclnet.qrx.core

import com.bclnet.jsonui.get
import com.bclnet.jsonui.hasFragmentReferences
import com.bclnet.jsonui.jsonOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse

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

class GlyphFragmentTest {
    private class FakeFetcher : GlyphFetcher {
        val responses = mutableMapOf<String, String>()
        var calls = 0
        override fun fetch(url: String): ByteArray {
            calls++
            return responses[url]?.toByteArray() ?: throw GlyphLookupException.Http(404)
        }
    }

    @Test fun fragmentsAreFetchedRelativeToTheDocument() {
        val fetcher = FakeFetcher()
        fetcher.responses["https://x/scenes/bush.json"] = """{"_ui":{"fragments":{"song":{"url":"x.wav"}}},"type":"Scene","actors":[{"id":"bush","body":{"${'$'}ref":"../bodies/box.json","sounds":{"song":{"${'$'}ref":"#song"}}},"mind":{"${'$'}ref":"minds/bush.json","budget":{"tokens":10}}}]}"""
        fetcher.responses["https://x/bodies/box.json"] = """{"model":"https://x/box.glb","scale":0.1,"animations":{"sing":{"${'$'}ref":"clips.json#/sing"}}}"""
        fetcher.responses["https://x/bodies/clips.json"] = """{"sing":{"clip":0,"speed":2}}"""
        fetcher.responses["https://x/scenes/minds/bush.json"] = """{"persona":"a shrub","budget":{"tokens":99,"perTurn":50}}"""
        val lookup = GlyphLookup(fetcher)
        var result: Result<GlyphDocument>? = null
        lookup.lookup(GlyphBarcode("https://x/scenes/bush.json")) { result = it }
        val document = result!!.getOrThrow()
        val ui = document.content as GlyphContent.Ui
        assertEquals(4, fetcher.calls)
        val actor = ui.document.root["actors"][0]
        assertEquals(JsonPrimitive("https://x/box.glb"), actor["body"]["model"])
        assertEquals(jsonOf(2), actor["body"]["animations"]["sing"]["speed"])
        assertEquals(JsonPrimitive("x.wav"), actor["body"]["sounds"]["song"]["url"])
        assertEquals(jsonOf(0.1), actor["body"]["scale"])
        assertEquals(JsonPrimitive("a shrub"), actor["mind"]["persona"])
        assertEquals(jsonOf(10), actor["mind"]["budget"]["tokens"])
        assertFalse(ui.document.value.hasFragmentReferences)
    }

    @Test fun missingFragmentFails() {
        val fetcher = FakeFetcher()
        fetcher.responses["https://x/a.json"] = """{"_button":{},"text":{"${'$'}ref":"b.json#/text"}}"""
        var result: Result<GlyphDocument>? = null
        GlyphLookup(fetcher).lookup(GlyphBarcode("https://x/a.json")) { result = it }
        assertTrue(result!!.exceptionOrNull()!!.message!!.contains("fragments: https://x/b.json"))
    }

    // A scanned code must not make the app read local files, or anything else that is not http(s) or blue.
    @Test fun onlyHttpAndBlueAreFetched() {
        for (ref in listOf("file:///etc/hosts#/text", "FILE:///etc/hosts", "ftp://x/a.json", "a.json#/text")) {
            val fetcher = FakeFetcher()
            fetcher.responses["https://x/a.json"] = """{"_button":{},"text":{"${'$'}ref":"$ref"}}"""
            val lookup = GlyphLookup(fetcher)
            // Inline (no base URL) for every reference; through a fetched document for the absolute ones.
            val inline = GlyphBarcode("size: *2\n\n{\"_button\":{},\"text\":{\"${'$'}ref\":\"$ref\"}}")
            for (barcode in listOf(inline) + (if (':' in ref) listOf(GlyphBarcode("https://x/a.json")) else emptyList())) {
                val before = fetcher.calls
                var result: Result<GlyphDocument>? = null
                lookup.lookup(barcode) { result = it }
                assertTrue(ref, result!!.exceptionOrNull()!!.message!!.contains("references are not fetched"))
                assertEquals("the fragment is never requested: $ref", if (barcode.location == null) 0 else 1, fetcher.calls - before)
            }
        }
        // The `url:` header takes any text, so the document's own scheme is checked too.
        for (location in listOf("file:///etc/hosts", "FILE:///etc/hosts", "ftp://x/a.json", "a.json")) {
            val fetcher = FakeFetcher()
            var result: Result<GlyphDocument>? = null
            GlyphLookup(fetcher).lookup(GlyphBarcode("url: $location")) { result = it }
            assertTrue(location, result!!.exceptionOrNull()!!.message!!.contains("are not fetched"))
            assertEquals(location, 0, fetcher.calls)
        }
    }

    @Test fun inlineDocumentsResolveLocalFragments() {
        var result: Result<GlyphDocument>? = null
        GlyphLookup(FakeFetcher()).lookup(GlyphBarcode("size: *2\n\n{\"_ui\":{\"fragments\":{\"t\":\"Hi\"}},\"type\":\"Text\",\"text\":{\"${'$'}ref\":\"#t\"}}")) { result = it }
        val ui = result!!.getOrThrow().content as GlyphContent.Ui
        assertEquals(JsonPrimitive("Hi"), ui.document.root["text"])
    }
}
