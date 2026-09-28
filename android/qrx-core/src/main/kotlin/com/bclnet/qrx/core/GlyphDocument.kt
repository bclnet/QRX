/*
 * GlyphDocument.kt
 * QRX
 *
 * A glyph document: JSON with one `_type` key (mirror of GlyphDocument.swift).
 */
package com.bclnet.qrx.core

import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.JsonDocument
import com.bclnet.jsonui.boolValue
import com.bclnet.jsonui.get
import com.bclnet.jsonui.isNull
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.parseJson
import com.bclnet.jsonui.stringValue
import com.bclnet.jsonui.toJsonString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class GlyphDocumentException(message: String) : IllegalArgumentException(message)

sealed class GlyphContent {
    data class Image(val url: String) : GlyphContent()
    data class Video(val url: String, val loop: Boolean) : GlyphContent()
    data class Web(val url: String) : GlyphContent()
    data class Button(val text: String, val action: JsonAction?) : GlyphContent()
    data class Ui(val document: JsonDocument) : GlyphContent()
    data class Unknown(val type: String) : GlyphContent()

    val typeName: String
        get() = when (this) {
            is Image -> "image"
            is Video -> "avplayer"
            is Web -> "web"
            is Button -> "button"
            is Ui -> "ui"
            is Unknown -> type
        }
}

data class GlyphDocument(val content: GlyphContent, val value: JsonElement) {
    fun toJsonString(pretty: Boolean = false): String = value.toJsonString(pretty)

    companion object {
        fun parse(json: String): GlyphDocument = fromValue(parseJson(json))

        fun fromValue(value: JsonElement): GlyphDocument {
            val obj = value as? JsonObject ?: throw GlyphDocumentException("glyph document must be a JSON object")
            val typeKey = obj.keys.sorted().firstOrNull { it.startsWith("_") } ?: throw GlyphDocumentException("glyph document has no _type key")
            val options: JsonElement = obj[typeKey]!!
            fun url(key: String = "url"): String = obj[key]?.stringValue ?: throw GlyphDocumentException("glyph document is missing \"$key\"")
            val content = when (typeKey.lowercase()) {
                "_image" -> GlyphContent.Image(url())
                "_avplayer", "_video" -> GlyphContent.Video(url(), options["loop"].boolValue ?: obj["loop"]?.boolValue ?: false)
                "_web" -> GlyphContent.Web(url())
                "_button" -> GlyphContent.Button(obj["text"]?.stringValue ?: throw GlyphDocumentException("glyph document is missing \"text\""), obj["action"]?.takeUnless { it.isNull }?.let { JsonAction.of(it) })
                "_ui" -> GlyphContent.Ui(JsonDocument.fromValue(value))
                else -> GlyphContent.Unknown(typeKey.substring(1))
            }
            return GlyphDocument(content, value)
        }

        fun button(text: String, action: JsonAction? = null): GlyphDocument {
            val value = if (action == null) jsonObjectOf("_button" to emptyMap<String, Any?>(), "text" to text)
            else JsonObject(mapOf("_button" to JsonObject(emptyMap()), "text" to JsonPrimitive(text), "action" to action.value))
            return GlyphDocument(GlyphContent.Button(text, action), value)
        }

        fun ui(document: JsonDocument): GlyphDocument = GlyphDocument(GlyphContent.Ui(document), document.value)
    }
}
