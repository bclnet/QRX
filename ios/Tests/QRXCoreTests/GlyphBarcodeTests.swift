import XCTest
@testable import QRXCore

final class GlyphBarcodeTests: XCTestCase {
    func testPortedCases() {
        let a = GlyphBarcode(payload: "size: 1x1\nurl: https://url.com\nmulti\n\nbody\n")
        XCTAssertEqual(a.sizes[.normal], GlyphSize(string: "1x1"))
        XCTAssertEqual(a.url, URL(string: "https://url.com"))
        XCTAssertTrue(a.multi)
        XCTAssertEqual(a.body, "body")
        XCTAssertNil(a.inlineDocument)

        let b = GlyphBarcode(payload: "size: 2x2\nhttps://url.com\n")
        XCTAssertEqual(b.url, URL(string: "https://url.com"))
        XCTAssertFalse(b.multi)
        XCTAssertNil(b.body)

        let c = GlyphBarcode(payload: "size: 3x3\n        \nhttps://url.com\n")
        XCTAssertEqual(c.sizes[.normal], GlyphSize(string: "3x3"))
        XCTAssertEqual(c.url, URL(string: "https://url.com"))
        XCTAssertFalse(c.multi)
        XCTAssertNil(c.body)
    }

    // A blank line ends the headers, but a body that is only a URL line is still the location.
    func testUrlAfterBlankLine() {
        let blue = GlyphBarcode(payload: "size: *3\n\nblue://Sky's Phone/glyph/ui-login\n")
        XCTAssertEqual(blue.blueTarget?.device, "Sky's Phone")
        XCTAssertNil(blue.body)
        XCTAssertNil(blue.inlineDocument)

        // An inline document after only `size:` lines stays the body.
        let inline = GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")
        XCTAssertNil(inline.url)
        XCTAssertEqual(inline.inlineDocument, #"{"_button":{},"text":"Inline"}"#)

        // So does a body with more than the URL, and a URL body when the headers already gave one.
        let text = GlyphBarcode(payload: "size: *2\n\nhttps://url.com\nand more")
        XCTAssertNil(text.url)
        XCTAssertEqual(text.body, "https://url.com\nand more")
        let both = GlyphBarcode(payload: "https://a.com\n\nhttps://b.com")
        XCTAssertEqual(both.url, URL(string: "https://a.com"))
        XCTAssertEqual(both.body, "https://b.com")
    }

    func testHeadersSizesAndInlineDocument() {
        let barcode = GlyphBarcode(payload: """
        size: *2
        size: 10l10x20b10:active
        X-Custom: value
        flag

        {"_button":{},"text":"Inline"}
        """)
        XCTAssertEqual(barcode.size(for: .normal), GlyphSize(string: "*2"))
        XCTAssertEqual(barcode.size(for: .active), GlyphSize(string: "10l10x20b10:active"))
        XCTAssertEqual(barcode.size(for: .focus), GlyphSize(string: "*2"))
        XCTAssertEqual(barcode.headers["x-custom"], "value")
        XCTAssertEqual(barcode.headers["flag"], "1")
        XCTAssertNil(barcode.url)
        XCTAssertEqual(barcode.inlineDocument, #"{"_button":{},"text":"Inline"}"#)
    }

    func testBareUrlAndPlainText() {
        let url = GlyphBarcode(payload: "https://raw.githubusercontent.com/bclnet/QRX/master/examples/image.json")
        XCTAssertEqual(url.url?.host, "raw.githubusercontent.com")
        XCTAssertFalse(url.isBluetooth)
        let text = GlyphBarcode(payload: "Hello world")
        XCTAssertNil(text.url)
        XCTAssertEqual(text.body, "Hello world")
        XCTAssertEqual(text.headers["hello world"], nil)
        XCTAssertEqual(GlyphBarcode.empty.body, nil)
    }

    func testBluetoothTarget() {
        let barcode = GlyphBarcode(payload: "size: *3\nblue://Sky's Phone/glyph/ui-login")
        XCTAssertTrue(barcode.isBluetooth)
        XCTAssertEqual(barcode.blueTarget?.device, "Sky's Phone")
        XCTAssertEqual(barcode.blueTarget?.path, "/glyph/ui-login")
        let any = GlyphBarcode(payload: "blue://*/glyphs")
        XCTAssertEqual(any.blueTarget?.device, "*")
        XCTAssertEqual(any.blueTarget?.path, "/glyphs")
    }
}
