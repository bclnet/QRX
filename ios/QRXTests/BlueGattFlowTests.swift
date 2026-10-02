import XCTest
import QRXCore
@testable import QRX

/// Simulates the GATT exchange between BlueCentral and BluePeripheral with the
/// core framer: request chunks in, routed response chunks out.
final class BlueGattFlowTests: XCTestCase {
    func testRequestResponseThroughChunks() throws {
        let service = BlueGlyphService()
        service.share(.button("Menu"), as: "menu")
        let mtu = 23
        // Central side: frame the request.
        let request = BlueRequest.get("/glyph/menu")
        let requestChunks = BlueFramer.frames(for: request.text, mtu: mtu)
        XCTAssertGreaterThan(requestChunks.count, 1)
        // Peripheral side: assemble, route, frame the response.
        let inbound = BlueAssembler()
        var text: String?
        for chunk in requestChunks { if let t = try inbound.append(chunk).first { text = t } }
        let response = service.router.handle(text: text!)
        XCTAssertEqual(response.statusCode, 200)
        let responseChunks = BlueFramer.frames(for: response.text, mtu: mtu)
        // Central side: assemble the response.
        let outbound = BlueAssembler()
        var responseText: String?
        for chunk in responseChunks { if let t = try outbound.append(chunk).first { responseText = t } }
        let parsed = try BlueResponse(text: responseText!)
        XCTAssertEqual(try GlyphDocument(json: parsed.content!).content, .button(text: "Menu", action: nil))
    }
}
