import XCTest
import JsonUICore
import QRXCore
@testable import QRX

@MainActor
final class AppModelTests: XCTestCase {
    func testHostActions() async throws {
        let model = AppModel()
        let runtime = JsonRuntime(actions: model.actions)
        runtime.context.perform(.host(name: "toast", args: ["message": "hi"]))
        await Task.yield()
        XCTAssertEqual(model.toast, "hi")
        runtime.context.perform(.host(name: "unknownAction", args: ["x": 1]))
        await Task.yield()
        XCTAssertEqual(model.toast, #"unknownAction {"x":1}"#)
        let led = model.actions.invoke("led", args: ["r": 1, "g": 2, "b": 3], context: runtime.context)
        XCTAssertEqual(led, ["written": false], "no board connected")
        XCTAssertEqual(model.bluetooth.led.color, LedColor(red: 1, green: 2, blue: 3))
    }

    func testResolveInlineGlyph() async {
        let model = AppModel()
        let barcode = GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")
        let expectation = expectation(description: "resolved")
        model.resolve(barcode) { document in
            XCTAssertEqual(document?.content, .button(text: "Inline", action: nil))
            expectation.fulfill()
        }
        await fulfillment(of: [expectation], timeout: 2)
        XCTAssertEqual(model.foundGlyphs.count, 1)
        XCTAssertEqual(model.foundGlyphs[0].status, "button")
        model.forgetGlyphs()
        XCTAssertTrue(model.foundGlyphs.isEmpty)
    }
}
