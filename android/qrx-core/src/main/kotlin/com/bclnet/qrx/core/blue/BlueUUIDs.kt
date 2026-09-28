/*
 * BlueUUIDs.kt
 * QRX
 *
 * GATT identifiers of the QRX service and of the Particle LED board from
 * the Particle example firmware, plus the LED colour value (mirror of BlueUUIDs.swift).
 */
package com.bclnet.qrx.core.blue

import com.bclnet.jsonui.doubleValue
import com.bclnet.jsonui.get
import com.bclnet.jsonui.jsonObjectOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

object BlueUUIDs {
    /** QRX BLUE/1.0 service. */
    val SERVICE: UUID = UUID.fromString("b4250500-fb4b-4746-b2b0-93f0e61122c6")
    /** Clients write request chunks here. */
    val REQUEST: UUID = UUID.fromString("b4250501-fb4b-4746-b2b0-93f0e61122c6")
    /** The server notifies response chunks here. */
    val RESPONSE: UUID = UUID.fromString("b4250502-fb4b-4746-b2b0-93f0e61122c6")
    /** Client Characteristic Configuration descriptor (enables notifications). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The local name advertised by a QRX server. */
    const val DEFAULT_LOCAL_NAME = "QRX"
}

/** Particle LED example board. */
object ParticleUUIDs {
    val LED_SERVICE: UUID = UUID.fromString("b4250400-fb4b-4746-b2b0-93f0e61122c6")
    val RED_LED: UUID = UUID.fromString("b4250401-fb4b-4746-b2b0-93f0e61122c6")
    val GREEN_LED: UUID = UUID.fromString("b4250402-fb4b-4746-b2b0-93f0e61122c6")
    val BLUE_LED: UUID = UUID.fromString("b4250403-fb4b-4746-b2b0-93f0e61122c6")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
}

data class LedColor(val red: Int, val green: Int, val blue: Int) {
    init { require(red in 0..255 && green in 0..255 && blue in 0..255) { "channels must be 0-255" } }

    enum class Channel(val characteristic: UUID) {
        Red(ParticleUUIDs.RED_LED), Green(ParticleUUIDs.GREEN_LED), Blue(ParticleUUIDs.BLUE_LED)
    }

    /** The single byte written to each LED characteristic. */
    fun bytes(channel: Channel): ByteArray = byteArrayOf(when (channel) { Channel.Red -> red; Channel.Green -> green; Channel.Blue -> blue }.toByte())

    val json: JsonObject get() = jsonObjectOf("r" to red, "g" to green, "b" to blue)

    companion object {
        val OFF = LedColor(0, 0, 0)

        /** Clamps arbitrary numbers into 0–255. */
        fun of(r: Double, g: Double, b: Double) = LedColor(clamp(r), clamp(g), clamp(b))

        /** Parses `{"r":..,"g":..,"b":..}` (also `red`/`green`/`blue`); null when any channel is missing. */
        fun fromJson(json: JsonElement): LedColor? {
            val r = json["r"].doubleValue ?: json["red"].doubleValue ?: return null
            val g = json["g"].doubleValue ?: json["green"].doubleValue ?: return null
            val b = json["b"].doubleValue ?: json["blue"].doubleValue ?: return null
            return of(r, g, b)
        }

        private fun clamp(v: Double): Int = if (v.isNaN() || v.isInfinite()) 0 else Math.round(v).coerceIn(0, 255).toInt()
    }
}
