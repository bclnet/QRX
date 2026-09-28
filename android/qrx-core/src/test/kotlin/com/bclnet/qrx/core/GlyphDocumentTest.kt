package com.bclnet.qrx.core

import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.JsonDocument
import com.bclnet.jsonui.JsonNode
import com.bclnet.jsonui.JsonRuntime
import com.bclnet.jsonui.JsonUIHeader
import com.bclnet.jsonui.jsonObjectOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlyphDocumentTest {
    private val examples: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "examples") }.first { it.isDirectory }

    @Test
    fun types() {
        assertEquals(GlyphContent.Image("https://x/y.png"), GlyphDocument.parse("""{"_image":{},"url":"https://x/y.png"}""").content)
        assertEquals(GlyphContent.Video("https://x/y.mp4", true), GlyphDocument.parse("""{"_avplayer":{"loop":true},"url":"https://x/y.mp4"}""").content)
        assertEquals(GlyphContent.Video("https://x/y.mp4", false), GlyphDocument.parse("""{"_avplayer":{},"url":"https://x/y.mp4"}""").content)
        assertEquals(GlyphContent.Web("https://x"), GlyphDocument.parse("""{"_web":{},"url":"https://x"}""").content)
        assertEquals(GlyphContent.Button("Tap", null), GlyphDocument.parse("""{"_button":{},"text":"Tap"}""").content)
        assertEquals(GlyphContent.Button("Tap", JsonAction.Host("toast")), GlyphDocument.parse("""{"_button":{},"text":"Tap","action":"toast"}""").content)
        assertEquals(GlyphContent.Unknown("hologram"), GlyphDocument.parse("""{"_hologram":{},"x":1}""").content)
        val ui = GlyphDocument.parse("""{"_ui":{"state":{"a":1}},"type":"Text","text":"${'$'}a"}""").content as GlyphContent.Ui
        assertEquals(JsonPrimitive(1), ui.document.header.state["a"])
        assertEquals(JsonNode.Kind.Text, ui.document.root.kind)
    }

    @Test
    fun errors() {
        assertThrows(GlyphDocumentException::class.java) { GlyphDocument.parse("[1]") }
        assertThrows(GlyphDocumentException::class.java) { GlyphDocument.parse("""{"url":"x"}""") }
        assertThrows(GlyphDocumentException::class.java) { GlyphDocument.parse("""{"_image":{}}""") }
        assertThrows(GlyphDocumentException::class.java) { GlyphDocument.parse("""{"_button":{}}""") }
        assertThrows(Exception::class.java) { GlyphDocument.parse("not json") }
    }

    @Test
    fun examplesParse() {
        val files = examples.listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue(files.size >= 6)
        val types = mutableSetOf<String>()
        for (file in files) {
            val document = GlyphDocument.parse(file.readText())
            types.add(document.content.typeName)
            (document.content as? GlyphContent.Ui)?.let { assertTrue(file.name, JsonRuntime(it.document).scriptErrors.isEmpty()) }
        }
        assertEquals(setOf("image", "avplayer", "web", "button", "ui"), types)
    }

    @Test
    fun builders() {
        val button = GlyphDocument.button("Go", JsonAction.Host("open", mapOf("url" to JsonPrimitive("https://x"))))
        assertEquals(button, GlyphDocument.parse(button.toJsonString()))
        val ui = GlyphDocument.ui(JsonDocument(JsonUIHeader(state = mapOf("n" to JsonPrimitive(1))), JsonNode(JsonNode.Kind.Text, mapOf("text" to JsonPrimitive("${'$'}n")))))
        assertEquals(ui.content, GlyphDocument.parse(ui.toJsonString()).content)
        assertEquals(jsonObjectOf("_button" to emptyMap<String, Any?>(), "text" to "Go"), GlyphDocument.button("Go").value)
    }
}
