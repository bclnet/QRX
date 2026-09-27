package com.bclnet.qrx.core

import com.bclnet.qrx.core.blue.BlueAssembler
import com.bclnet.qrx.core.blue.BlueAssemblerException
import com.bclnet.qrx.core.blue.BlueFramer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class BlueFramerTest {
    private fun assemble(frames: List<ByteArray>): String? {
        val assembler = BlueAssembler()
        var result: String? = null
        for (frame in frames) assembler.append(frame)?.let { result = it }
        assertTrue(assembler.isIdle)
        return result
    }

    @Test
    fun framesAndReassembly() {
        val text = "Hello BLUE! ".repeat(20)
        val frames = BlueFramer.frames(text, 23)
        assertEquals(ceil((text.toByteArray().size + 4) / 20.0).toInt(), frames.size)
        assertTrue(frames.all { it.size <= 20 })
        val length = text.toByteArray().size
        assertEquals(listOf((length and 0xff).toByte(), (length shr 8).toByte(), 0.toByte(), 0.toByte()), frames[0].take(4))
        assertEquals(text, assemble(frames))
    }

    @Test
    fun largerMtuAndEmptyMessage() {
        val frames = BlueFramer.frames("abc", 185)
        assertEquals(1, frames.size)
        assertEquals(7, frames[0].size)
        assertEquals("abc", assemble(frames))
        assertEquals("", assemble(BlueFramer.frames("", 23)))
    }

    @Test
    fun backToBackMessagesInOneChunk() {
        val a = BlueFramer.frames("one", 100)[0]
        val b = BlueFramer.frames("two", 100)[0]
        val assembler = BlueAssembler()
        assertEquals("one", assembler.append(a + b.copyOfRange(0, 2)))
        assertEquals("two", assembler.append(b.copyOfRange(2, b.size)))
    }

    @Test
    fun rejectsAbsurdLength() {
        val assembler = BlueAssembler()
        assertThrows(BlueAssemblerException::class.java) { assembler.append(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f)) }
        assertTrue(assembler.isIdle)
    }

    @Test
    fun unicode() {
        val text = "héllo ✓ 日本語"
        assertEquals(text, assemble(BlueFramer.frames(text, 23)))
    }
}
