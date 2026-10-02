/*
 * BlueFramer.kt
 * QRX
 *
 * Length-prefixed chunk framing for BLUE messages over GATT characteristics
 * (mirror of BlueFramer.swift): `UInt32 little-endian length` + UTF-8 bytes,
 * split into chunks of at most MTU - 3 bytes.
 */
package com.bclnet.qrx.core.blue

import java.io.ByteArrayOutputStream

class BlueAssemblerException(message: String) : IllegalStateException(message)

object BlueFramer {
    const val DEFAULT_MTU = 23
    const val HEADER_SIZE = 4
    /** The largest message accepted by the assembler (protects against bad length prefixes). */
    const val MAX_MESSAGE_SIZE = 4 * 1024 * 1024

    fun chunkSize(mtu: Int): Int = maxOf(1, mtu - 3)

    /** Splits `text` into chunks ready to be written or notified. */
    fun frames(text: String, mtu: Int = DEFAULT_MTU): List<ByteArray> = frames(text.toByteArray(Charsets.UTF_8), chunkSize(mtu))

    fun frames(payload: ByteArray, chunkSize: Int): List<ByteArray> {
        val length = payload.size
        val message = ByteArray(HEADER_SIZE + length)
        message[0] = (length and 0xff).toByte()
        message[1] = ((length shr 8) and 0xff).toByte()
        message[2] = ((length shr 16) and 0xff).toByte()
        message[3] = ((length shr 24) and 0xff).toByte()
        payload.copyInto(message, HEADER_SIZE)
        val size = maxOf(1, chunkSize)
        return message.indices.step(size).map { offset -> message.copyOfRange(offset, minOf(offset + size, message.size)) }
    }
}

/** Reassembles chunks into messages. One instance per connection and direction. */
class BlueAssembler {
    private val buffer = ByteArrayOutputStream()
    private var expected: Int? = null

    val isIdle: Boolean get() = buffer.size() == 0 && expected == null

    /**
     * Appends a chunk; returns the messages it completed, in order. Usually none or one, but a chunk
     * can carry the end of one message and all of the next.
     */
    fun append(chunk: ByteArray): List<String> {
        buffer.write(chunk)
        val messages = mutableListOf<String>()
        while (true) {
            val bytes = buffer.toByteArray()
            if (expected == null && bytes.size >= BlueFramer.HEADER_SIZE) {
                val value = (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8) or ((bytes[2].toInt() and 0xff) shl 16) or ((bytes[3].toInt() and 0xff) shl 24)
                if (value < 0 || value > BlueFramer.MAX_MESSAGE_SIZE) {
                    reset()
                    throw BlueAssemblerException("BLUE: message length $value exceeds ${BlueFramer.MAX_MESSAGE_SIZE}")
                }
                expected = value
            }
            val length = expected ?: return messages
            val end = BlueFramer.HEADER_SIZE + length
            if (bytes.size < end) return messages
            messages.add(String(bytes, BlueFramer.HEADER_SIZE, length, Charsets.UTF_8))
            reset()
            buffer.write(bytes, end, bytes.size - end)
        }
    }

    fun reset() {
        buffer.reset()
        expected = null
    }
}
