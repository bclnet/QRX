package com.bclnet.qrx.core

import com.bclnet.qrx.core.blue.BlueLineParser
import com.bclnet.qrx.core.blue.BlueProtocolException
import com.bclnet.qrx.core.blue.BlueRequest
import com.bclnet.qrx.core.blue.BlueResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueProtocolTest {
    @Test
    fun requestRoundTrip() {
        val request = BlueRequest("post", "/glyph/menu?x=1&name=a%20b", mapOf("Content-Type" to "application/json"), """{"_button":{},"text":"hi"}""")
        val text = request.text
        assertTrue(text.startsWith("POST /glyph/menu?x=1&name=a%20b BLUE/1.0\nContent-Length: 26\nContent-Type: application/json\n\n"))
        val parsed = BlueRequest.parse(text)
        assertEquals("POST", parsed.method)
        assertEquals("/glyph/menu?x=1&name=a%20b", parsed.uri)
        assertEquals("/glyph/menu", parsed.path)
        assertEquals(mapOf("x" to "1", "name" to "a b"), parsed.query)
        assertEquals("application/json", parsed.header("content-type"))
        assertEquals(request.body, parsed.body)
    }

    @Test
    fun requestLineForms() {
        assertEquals("/ping", BlueRequest.parse("GET /ping BLUE/1.0\n\n").uri)
        assertEquals("GET", BlueRequest.parse("get /ping\r\n\r\n").method)
        assertEquals("/a b", BlueRequest.parse("GET /a b BLUE/1.0\n").uri)
        assertNull(BlueRequest.parse("GET /ping BLUE/1.0\n\n").body)
        assertThrows(BlueProtocolException::class.java) { BlueRequest.parse("") }
        assertThrows(BlueProtocolException::class.java) { BlueRequest.parse("GET\n") }
        assertThrows(BlueProtocolException::class.java) { BlueRequest.parse("GET / BLUE/2.0\n") }
        assertThrows(BlueProtocolException::class.java) { BlueRequest.parse("GET / BLUE/1.0\nnot a header\n") }
    }

    @Test
    fun responseRoundTrip() {
        val response = BlueResponse.json("""{"ok":true}""")
        assertEquals("BLUE/1.0 200 OK\nContent-Length: 11\nContent-Type: application/json\n\n{\"ok\":true}", response.text)
        val parsed = BlueResponse.parse(response.text)
        assertEquals(200, parsed.statusCode)
        assertEquals("OK", parsed.statusDescription)
        assertEquals("""{"ok":true}""", parsed.content)
        assertTrue(parsed.isSuccess)
        assertEquals("BLUE/1.0 404 Not Found\n\n", BlueResponse(404).finish())
        assertEquals("Service Unavailable", BlueResponse.parse("BLUE/1.0 503\n\n").statusDescription)
        assertThrows(BlueProtocolException::class.java) { BlueResponse.parse("HTTP/1.1 200 OK\n\n") }
        assertThrows(BlueProtocolException::class.java) { BlueResponse.parse("BLUE/1.0 abc\n\n") }
        assertEquals("Bad Request", BlueResponse.error(400).content)
    }

    @Test
    fun contentLengthTruncatesTrailingBytes() {
        val message = BlueLineParser.parse("BLUE/1.0 200 OK\nContent-Length: 3\n\nabcXYZ")
        assertEquals(listOf("BLUE/1.0 200 OK", "Content-Length: 3"), message.lines)
        assertEquals("abc", message.body)
    }
}
