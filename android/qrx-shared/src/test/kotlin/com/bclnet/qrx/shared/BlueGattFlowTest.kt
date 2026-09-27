package com.bclnet.qrx.shared

import com.bclnet.qrx.core.GlyphContent
import com.bclnet.qrx.core.GlyphDocument
import com.bclnet.qrx.core.blue.BlueAssembler
import com.bclnet.qrx.core.blue.BlueFramer
import com.bclnet.qrx.core.blue.BlueGlyphService
import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulates the GATT exchange between BlueGattClient and BlueGattServer with the core framer. */
class BlueGattFlowTest {
    @Test
    fun requestResponseThroughChunks() {
        val service = BlueGlyphService()
        service.share(GlyphDocument.button("Menu"), "menu")
        val mtu = 23
        val requestChunks = BlueFramer.frames(BlueRequest.get("/glyph/menu").text, mtu)
        assertTrue(requestChunks.size > 1)
        val inbound = BlueAssembler()
        var text: String? = null
        requestChunks.forEach { chunk -> inbound.append(chunk)?.let { text = it } }
        val response = service.router.handle(text!!)
        assertEquals(200, response.statusCode)
        val outbound = BlueAssembler()
        var responseText: String? = null
        BlueFramer.frames(response.text, mtu).forEach { chunk -> outbound.append(chunk)?.let { responseText = it } }
        val parsed = BlueResponse.parse(responseText!!)
        assertEquals(GlyphContent.Button("Menu", null), GlyphDocument.parse(parsed.content!!).content)
    }
}
