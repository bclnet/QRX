import XCTest
import QRXCore
@testable import QRX

final class GlyphPlaneTests: XCTestCase {
    func testDefaultMatchesCode() {
        let plane = GlyphPlane(size: .zero, physicalWidth: 0.06, physicalHeight: 0.06)
        XCTAssertEqual(plane.width, 0.06, accuracy: 1e-9)
        XCTAssertEqual(plane.height, 0.06, accuracy: 1e-9)
        XCTAssertEqual(plane.offsetX, 0, accuracy: 1e-9)
        XCTAssertEqual(plane.offsetY, 0, accuracy: 1e-9)
    }

    func testMultiplesAndAnchors() {
        let twice = GlyphPlane(size: GlyphSize(string: "*2")!, physicalWidth: 0.06, physicalHeight: 0.06)
        XCTAssertEqual(twice.width, 0.12, accuracy: 1e-9)
        XCTAssertEqual(twice.height, 0.12, accuracy: 1e-9)
        // "10l10x20b10": 10 code units wide, anchored left and moved 10 units right; 20 high, anchored bottom and moved 10 units down.
        let anchored = GlyphPlane(size: GlyphSize(string: "10l10x20b10")!, physicalWidth: 0.06, physicalHeight: 0.06)
        XCTAssertEqual(anchored.width, 0.6, accuracy: 1e-9)
        XCTAssertEqual(anchored.height, 1.2, accuracy: 1e-9)
        XCTAssertEqual(anchored.offsetX, -(0.06 - 0.6) / 2 + 0.6, accuracy: 1e-9)
        XCTAssertEqual(anchored.offsetY, -((0.06 - 1.2) / 2 + 0.6), accuracy: 1e-9)
    }
}
