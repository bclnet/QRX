package com.bclnet.qrx.shared

import com.bclnet.qrx.core.GlyphSize
import com.bclnet.qrx.shared.scan.Box
import com.bclnet.qrx.shared.scan.GlyphPlacement
import com.bclnet.qrx.shared.scan.ImageToView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlyphPlacementTest {
    private val code = Box(100f, 200f, 160f, 260f) // a 60x60 code

    @Test
    fun defaultCoversTheCode() {
        assertEquals(code, GlyphPlacement.place(code, GlyphSize.zero))
    }

    @Test
    fun multiplesAndAbsoluteUnits() {
        val twice = GlyphPlacement.place(code, GlyphSize.parse("*2")!!)
        assertEquals(Box(70f, 170f, 190f, 290f), twice)
        // "3x2": three code units wide, two high, centred.
        val units = GlyphPlacement.place(code, GlyphSize.parse("3x2")!!)
        assertEquals(180f, units.width, 1e-4f)
        assertEquals(120f, units.height, 1e-4f)
        assertEquals(code.centerX, units.centerX, 1e-4f)
    }

    @Test
    fun anchorsAndOffsets() {
        // "10l10x20b10": 10 units wide anchored left then moved 10 units right; 20 high anchored bottom then moved 10 units down.
        val box = GlyphPlacement.place(code, GlyphSize.parse("10l10x20b10")!!)
        assertEquals(600f, box.width, 1e-3f)
        assertEquals(1200f, box.height, 1e-3f)
        assertEquals(code.centerX + (-(60f - 600f) / 2) + 600f, box.centerX, 1e-3f)
        assertEquals(code.centerY + ((60f - 1200f) / 2) + 600f, box.centerY, 1e-3f)
        // "*1r" keeps the width and aligns the right edges (no-op for equal sizes).
        assertEquals(code, GlyphPlacement.place(code, GlyphSize.parse("*1r")!!))
        // "*2r": twice as wide, right edge of content on the right edge of the code.
        val right = GlyphPlacement.place(code, GlyphSize.parse("*2rx*1")!!)
        assertEquals(code.right, right.right, 1e-3f)
    }

    @Test
    fun imageToViewFillCenter() {
        val mapping = ImageToView(1280f, 720f, 1080f, 1920f)
        assertEquals(1920f / 720f, mapping.scale, 1e-5f)
        val mapped = mapping.map(Box(0f, 0f, 1280f, 720f))
        assertEquals(0f, mapped.top, 1e-3f)
        assertEquals(1920f, mapped.bottom, 1e-3f)
        assertEquals(1080f, mapped.centerX * 2, 1e-2f)
    }

    @Test
    fun boundsOfCorners() {
        assertEquals(Box(1f, 2f, 5f, 9f), GlyphPlacement.bounds(listOf(1f to 2f, 5f to 3f, 4f to 9f, 2f to 8f)))
        assertNull(GlyphPlacement.bounds(emptyList()))
    }
}
