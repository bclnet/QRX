import XCTest
@testable import QRXCore

final class GlyphSizeTests: XCTestCase {
    typealias D = GlyphSize.Dimension

    // Covers the size grammar, including the default and selector forms.
    let cases: [(String, GlyphSelector, D, D, String)] = [
        ("~", .normal, D(multiple: true, value: 1), D(multiple: true, value: 1), "~"),
        ("1", .normal, D(multiple: false, value: 1), D(multiple: false, value: 1), "1x1"),
        ("1:active", .active, D(multiple: false, value: 1), D(multiple: false, value: 1), "1x1:active"),
        ("1c1", .normal, D(multiple: false, value: 1, anchor: .center, offset: 1), D(multiple: false, value: 1, anchor: .center, offset: 1), "1c1x1c1"),
        ("*1r", .normal, D(multiple: true, value: 1, anchor: .end), D(multiple: true, value: 1, anchor: .end), "*1rx*1b"),
        ("1x2", .normal, D(multiple: false, value: 1), D(multiple: false, value: 2), "1x2"),
        ("1l1x2c2", .normal, D(multiple: false, value: 1, anchor: .start, offset: 1), D(multiple: false, value: 2, anchor: .center, offset: 2), "1l1x2c2"),
        ("1c-1x2", .normal, D(multiple: false, value: 1, anchor: .center, offset: -1), D(multiple: false, value: 2), "1c-1x2"),
        ("*1r+1x2", .normal, D(multiple: true, value: 1, anchor: .end, offset: 1), D(multiple: false, value: 2), "*1r1x2"),
        ("+1.5c2.3x2", .normal, D(multiple: false, value: 1.5, anchor: .center, offset: 2.3), D(multiple: false, value: 2), "1.5c2.3x2"),
        ("*+1.23x*-2.23", .normal, D(multiple: true, value: 1.23), D(multiple: true, value: -2.23), "*1.23x*-2.23"),
        ("*-1.2l-5.6x*+2.3b+5.3", .normal, D(multiple: true, value: -1.2, anchor: .start, offset: -5.6), D(multiple: true, value: 2.3, anchor: .end, offset: 5.3), "*-1.2l-5.6x*2.3b5.3"),
        ("10l10x20b10", .normal, D(multiple: false, value: 10, anchor: .start, offset: 10), D(multiple: false, value: 20, anchor: .end, offset: 10), "10l10x20b10"),
        ("*2x*3:focus", .focus, D(multiple: true, value: 2), D(multiple: true, value: 3), "*2x*3:focus"),
    ]

    func testParsing() {
        for (text, selector, width, height, description) in cases {
            guard let size = GlyphSize(string: text) else { XCTFail(text); continue }
            XCTAssertEqual(size.selector, selector, text)
            XCTAssertEqual(size.width, width, text)
            XCTAssertEqual(size.height, height, text)
            XCTAssertEqual(size.description, description, text)
            XCTAssertEqual(GlyphSize(string: size.description), size, "round trip \(text)")
        }
    }

    func testInvalid() {
        XCTAssertNil(GlyphSize(string: "abc"))
        XCTAssertNil(GlyphSize(string: "1lx"))
        XCTAssertNil(GlyphSize(string: "1x2q3"))
    }

    func testResolve() {
        let twice = GlyphSize(string: "*2")!
        XCTAssertEqual(twice.width.resolve(code: 10).size, 20)
        XCTAssertEqual(twice.width.resolve(code: 10).offset, 0)
        let anchored = GlyphSize(string: "4lx6b1")!
        XCTAssertEqual(anchored.width.resolve(code: 10).size, 4)
        XCTAssertEqual(anchored.width.resolve(code: 10).offset, -3)   // left edge aligned with the code's left edge
        XCTAssertEqual(anchored.height.resolve(code: 10).offset, 3)   // bottom aligned, plus 1
    }

    func testCodableAndSelectorLookup() throws {
        let size = GlyphSize(string: "*2x*3:active")!
        let data = try JSONEncoder().encode([size])
        XCTAssertEqual(String(decoding: data, as: UTF8.self), #"["*2x*3:active"]"#)
        XCTAssertEqual(try JSONDecoder().decode([GlyphSize].self, from: data), [size])
        let sizes: [GlyphSelector: GlyphSize] = [.active: size]
        XCTAssertEqual(sizes.size(for: .active), size)
        XCTAssertEqual(sizes.size(for: .focus), .zero)
    }
}
