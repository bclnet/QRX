package com.bclnet.qrx.core

import com.bclnet.qrx.core.GlyphSize.Anchor
import com.bclnet.qrx.core.GlyphSize.Dimension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlyphSizeTest {
    private data class Case(val text: String, val selector: GlyphSelector, val width: Dimension, val height: Dimension, val description: String)

    // Covers the size grammar, including the default and selector forms.
    private val cases = listOf(
        Case("~", GlyphSelector.Normal, Dimension(true, 1.0), Dimension(true, 1.0), "~"),
        Case("1", GlyphSelector.Normal, Dimension(false, 1.0), Dimension(false, 1.0), "1x1"),
        Case("1:active", GlyphSelector.Active, Dimension(false, 1.0), Dimension(false, 1.0), "1x1:active"),
        Case("1c1", GlyphSelector.Normal, Dimension(false, 1.0, Anchor.Center, 1.0), Dimension(false, 1.0, Anchor.Center, 1.0), "1c1x1c1"),
        Case("*1r", GlyphSelector.Normal, Dimension(true, 1.0, Anchor.End), Dimension(true, 1.0, Anchor.End), "*1rx*1b"),
        Case("1x2", GlyphSelector.Normal, Dimension(false, 1.0), Dimension(false, 2.0), "1x2"),
        Case("1l1x2c2", GlyphSelector.Normal, Dimension(false, 1.0, Anchor.Start, 1.0), Dimension(false, 2.0, Anchor.Center, 2.0), "1l1x2c2"),
        Case("1c-1x2", GlyphSelector.Normal, Dimension(false, 1.0, Anchor.Center, -1.0), Dimension(false, 2.0), "1c-1x2"),
        Case("*1r+1x2", GlyphSelector.Normal, Dimension(true, 1.0, Anchor.End, 1.0), Dimension(false, 2.0), "*1r1x2"),
        Case("+1.5c2.3x2", GlyphSelector.Normal, Dimension(false, 1.5, Anchor.Center, 2.3), Dimension(false, 2.0), "1.5c2.3x2"),
        Case("*+1.23x*-2.23", GlyphSelector.Normal, Dimension(true, 1.23), Dimension(true, -2.23), "*1.23x*-2.23"),
        Case("*-1.2l-5.6x*+2.3b+5.3", GlyphSelector.Normal, Dimension(true, -1.2, Anchor.Start, -5.6), Dimension(true, 2.3, Anchor.End, 5.3), "*-1.2l-5.6x*2.3b5.3"),
        Case("10l10x20b10", GlyphSelector.Normal, Dimension(false, 10.0, Anchor.Start, 10.0), Dimension(false, 20.0, Anchor.End, 10.0), "10l10x20b10"),
        Case("*2x*3:focus", GlyphSelector.Focus, Dimension(true, 2.0), Dimension(true, 3.0), "*2x*3:focus"),
    )

    @Test
    fun parsing() {
        for (c in cases) {
            val size = GlyphSize.parse(c.text)
            assertNotNull(c.text, size)
            assertEquals(c.text, c.selector, size!!.selector)
            assertEquals(c.text, c.width, size.width)
            assertEquals(c.text, c.height, size.height)
            assertEquals(c.text, c.description, size.description)
            assertEquals("round trip ${c.text}", size, GlyphSize.parse(size.description))
        }
    }

    @Test
    fun invalid() {
        assertNull(GlyphSize.parse("abc"))
        assertNull(GlyphSize.parse("1lx"))
        assertNull(GlyphSize.parse("1x2q3"))
    }

    @Test
    fun resolve() {
        val twice = GlyphSize.parse("*2")!!
        assertEquals(20.0 to 0.0, twice.width.resolve(10.0))
        val anchored = GlyphSize.parse("4lx6b1")!!
        assertEquals(4.0 to -3.0, anchored.width.resolve(10.0))
        assertEquals(6.0 to 3.0, anchored.height.resolve(10.0))
    }

    @Test
    fun selectorLookup() {
        val size = GlyphSize.parse("*2x*3:active")!!
        val sizes = mapOf(GlyphSelector.Active to size)
        assertEquals(size, sizes.sizeFor(GlyphSelector.Active))
        assertEquals(GlyphSize.zero, sizes.sizeFor(GlyphSelector.Focus))
    }
}
