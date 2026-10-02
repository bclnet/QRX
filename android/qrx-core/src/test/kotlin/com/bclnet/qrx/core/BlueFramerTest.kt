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
        for (frame in frames) assembler.append(frame).lastOrNull()?.let { result = it }
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
        assertEquals(listOf("one"), assembler.append(a + b.copyOfRange(0, 2)))
        assertEquals(listOf("two"), assembler.append(b.copyOfRange(2, b.size)))
    }

    // A chunk can hold more than one whole message; none of them may be dropped.
    @Test
    fun wholeMessagesInOneChunk() {
        val chunk = listOf("one", "", "three").fold(ByteArray(0)) { all, text -> all + BlueFramer.frames(text, 100)[0] }
        val assembler = BlueAssembler()
        assertEquals(listOf("one", "", "three"), assembler.append(chunk))
        assertTrue(assembler.isIdle)
        // The tail of one message, a whole one, and the start of the next.
        val a = BlueFramer.frames("alpha", 100)[0]; val b = BlueFramer.frames("beta", 100)[0]; val c = BlueFramer.frames("gamma", 100)[0]
        assertEquals(emptyList<String>(), assembler.append(a.copyOfRange(0, 3)))
        assertEquals(listOf("alpha", "beta"), assembler.append(a.copyOfRange(3, a.size) + b + c.copyOfRange(0, 5)))
        assertEquals(listOf("gamma"), assembler.append(c.copyOfRange(5, c.size)))
        assertTrue(assembler.isIdle)
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
