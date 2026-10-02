package com.bclnet.qrx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphBarcodeTest {
    @Test
    fun portedCases() {
        val a = GlyphBarcode("size: 1x1\nurl: https://url.com\nmulti\n\nbody\n")
        assertEquals(GlyphSize.parse("1x1"), a.sizes[GlyphSelector.Normal])
        assertEquals("https://url.com", a.location)
        assertEquals("url.com", a.url?.host)
        assertTrue(a.multi)
        assertEquals("body", a.body)
        assertNull(a.inlineDocument)

        val b = GlyphBarcode("size: 2x2\nhttps://url.com\n")
        assertEquals("https://url.com", b.location)
        assertFalse(b.multi)
        assertNull(b.body)

        val c = GlyphBarcode("size: 3x3\n        \nhttps://url.com\n")
        assertEquals(GlyphSize.parse("3x3"), c.sizes[GlyphSelector.Normal])
        assertEquals("https://url.com", c.location)
        assertFalse(c.multi)
        assertNull(c.body)
    }

    // A blank line ends the headers, but a body that is only a URL line is still the location.
    @Test
    fun urlAfterBlankLine() {
        val blue = GlyphBarcode("size: *3\n\nblue://Sky's Phone/glyph/ui-login\n")
        assertEquals("Sky's Phone", blue.blueTarget?.device)
        assertNull(blue.body)
        assertNull(blue.inlineDocument)

        // An inline document after only `size:` lines stays the body.
        val inline = GlyphBarcode("size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")
        assertNull(inline.location)
        assertEquals("{\"_button\":{},\"text\":\"Inline\"}", inline.inlineDocument)

        // So does a body with more than the URL, and a URL body when the headers already gave one.
        val text = GlyphBarcode("size: *2\n\nhttps://url.com\nand more")
        assertNull(text.location)
        assertEquals("https://url.com\nand more", text.body)
        val both = GlyphBarcode("https://a.com\n\nhttps://b.com")
        assertEquals("https://a.com", both.location)
        assertEquals("https://b.com", both.body)
    }

    @Test
    fun headersSizesAndInlineDocument() {
        val barcode = GlyphBarcode("size: *2\nsize: 10l10x20b10:active\nX-Custom: value\nflag\n\n{\"_button\":{},\"text\":\"Inline\"}")
        assertEquals(GlyphSize.parse("*2"), barcode.size(GlyphSelector.Normal))
        assertEquals(GlyphSize.parse("10l10x20b10:active"), barcode.size(GlyphSelector.Active))
        assertEquals(GlyphSize.parse("*2"), barcode.size(GlyphSelector.Focus))
        assertEquals("value", barcode.headers["x-custom"])
        assertEquals("1", barcode.headers["flag"])
        assertNull(barcode.location)
        assertEquals("{\"_button\":{},\"text\":\"Inline\"}", barcode.inlineDocument)
    }

    @Test
    fun bareUrlAndPlainText() {
        val url = GlyphBarcode("https://raw.githubusercontent.com/bclnet/QRX/master/examples/image.json")
        assertEquals("raw.githubusercontent.com", url.url?.host)
        assertFalse(url.isBluetooth)
        val text = GlyphBarcode("Hello world")
        assertNull(text.location)
        assertEquals("Hello world", text.body)
        assertTrue(text.headers.isEmpty())
        assertNull(GlyphBarcode.empty.body)
    }

    @Test
    fun bluetoothTarget() {
        val barcode = GlyphBarcode("size: *3\nblue://Sky's Phone/glyph/ui-login")
        assertTrue(barcode.isBluetooth)
        assertEquals(GlyphBarcode.BlueTarget("Sky's Phone", "/glyph/ui-login"), barcode.blueTarget)
        assertEquals(GlyphBarcode.BlueTarget("*", "/glyphs"), GlyphBarcode("blue://*/glyphs").blueTarget)
    }
}
