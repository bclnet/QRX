import XCTest
import ARKit
import Combine
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
    }

    func testResolveInlineGlyph() async {
        let model = AppModel()
        let barcode = GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")
        let expectation = expectation(description: "resolved")
        model.resolve(barcode) { document, error in
            XCTAssertEqual(document?.content, .button(text: "Inline", action: nil))
            XCTAssertNil(error)
            expectation.fulfill()
        }
        await fulfillment(of: [expectation], timeout: 2)
        XCTAssertEqual(model.foundGlyphs.count, 1)
        XCTAssertEqual(model.foundGlyphs[0].status, "button")
        model.forgetGlyphs()
        XCTAssertTrue(model.foundGlyphs.isEmpty)
    }

    // A second sighting while the lookup is still running is answered when it finishes, not left waiting.
    func testResolveAnswersEverySightingOfALoadingCode() async {
        let model = AppModel()
        let barcode = GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")
        let both = expectation(description: "both sightings resolved")
        both.expectedFulfillmentCount = 2
        for _ in 0..<2 {
            model.resolve(barcode) { document, _ in
                XCTAssertEqual(document?.content, .button(text: "Inline", action: nil))
                both.fulfill()
            }
        }
        await fulfillment(of: [both], timeout: 2)
        XCTAssertEqual(model.foundGlyphs.count, 1)
    }

    // The chrome observes the model but reads the mic and Bluetooth state from its services,
    // so a change in either has to be announced by the model.
    func testModelAnnouncesSpeechAndBluetoothChanges() {
        let model = AppModel()
        var changes = 0
        let observer = model.objectWillChange.sink { changes += 1 }
        model.speech.error = "Microphone access is not allowed."
        XCTAssertEqual(changes, 1, "speech")
        model.bluetooth.objectWillChange.send()
        XCTAssertEqual(changes, 2, "bluetooth")
        observer.cancel()
    }

    func testSessionFailureAndInterruptionTitles() {
        let model = AppModel()
        model.title = "Showing image glyph"
        model.sessionInterrupted()
        XCTAssertEqual(model.title, AppModel.interruptedTitle)
        model.sessionInterrupted()
        model.sessionInterruptionEnded()
        XCTAssertEqual(model.title, "Showing image glyph", "back to what it said before the pause")

        // A title set during the pause is kept.
        model.sessionInterrupted()
        model.title = "Glyph failed: HTTP 404"
        model.sessionInterruptionEnded()
        XCTAssertEqual(model.title, "Glyph failed: HTTP 404")

        // A failure replaces the title and is not undone by a later resume.
        model.sessionInterrupted()
        model.sessionFailed(ARGlyphView.Coordinator.message(forFailure: ARError(.cameraUnauthorized)))
        model.sessionInterruptionEnded()
        XCTAssertEqual(model.title, "QRX needs the camera. Allow it in Settings, under QRX.")
        XCTAssertTrue(ARGlyphView.Coordinator.message(forFailure: ARError(.sensorFailed)).hasPrefix("AR stopped: "))
        model.forgetGlyphs()
        XCTAssertEqual(model.title, "Look for a QR code.")
    }

    func testForgetIsCounted() async {
        let model = AppModel()
        XCTAssertEqual(model.forgetCount, 0)
        let resolved = expectation(description: "resolved")
        model.resolve(GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")) { _, _ in resolved.fulfill() }
        await fulfillment(of: [resolved], timeout: 2)
        XCTAssertEqual(model.forgetCount, 0, "seeing a code is not a forget")
        model.forgetGlyphs()
        XCTAssertEqual(model.forgetCount, 1)
    }

    // A failed lookup reports why, so the glyph can show it instead of loading forever.
    func testResolveReportsTheFailure() async {
        let model = AppModel()
        let barcode = GlyphBarcode(payload: "size: *2\n\n{\"text\":\"no type key\"}")
        let expectation = expectation(description: "resolved")
        model.resolve(barcode) { document, error in
            XCTAssertNil(document)
            XCTAssertEqual(error, "glyph document has no _type key")
            expectation.fulfill()
        }
        await fulfillment(of: [expectation], timeout: 2)
        XCTAssertEqual(model.foundGlyphs[0].status, "glyph document has no _type key")
        // A second sighting of the same code gets the same answer.
        var again: String?
        model.resolve(barcode) { _, error in again = error }
        XCTAssertEqual(again, "glyph document has no _type key")
    }
}
