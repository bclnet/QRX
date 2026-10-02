import XCTest
import ARKit
import SceneKit
import QRXCore
@testable import QRX

@MainActor
final class GlyphFactoryTests: XCTestCase {
    private let physical = CGSize(width: 0.06, height: 0.06)
    private let parent = UIView(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
    private lazy var factory: GlyphFactory = {
        let factory = GlyphFactory(model: AppModel())
        factory.parent = parent
        return factory
    }()

    private func result(_ payload: String, document: String? = nil, error: String? = nil) throws -> BarcodeResult {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { _ in }.cgImage!
        return BarcodeResult(version: 0, referenceImage: ARReferenceImage(image, orientation: .up, physicalWidth: physical.width),
                             barcode: GlyphBarcode(payload: payload), document: try document.map { try GlyphDocument(json: $0) }, error: error)
    }

    /// The view drawn on a glyph node's plane.
    private func hostedView(on node: SCNNode) -> UIView? {
        node.childNodes.first?.geometry?.firstMaterial?.diffuse.contents as? UIView
    }

    // The node ARKit holds is built before the lookup finishes; it is filled again when the document arrives.
    func testDocumentArrivingLaterReplacesThePlaceholder() throws {
        let node = factory.node(physical: physical, result: try result("https://x/a.json"))
        let placeholder = try XCTUnwrap(hostedView(on: node))
        XCTAssertEqual(parent.subviews, [placeholder])

        factory.refresh(try result("https://x/a.json", document: #"{"_button":{},"text":"Hi"}"#))
        let content = try XCTUnwrap(hostedView(on: node))
        XCTAssertFalse(content === placeholder)
        XCTAssertEqual(parent.subviews, [content], "the placeholder's view is gone")
        XCTAssertEqual(node.childNodes.count, 1)

        // A failure is a new card too, and nothing is left behind when a video takes the plane.
        factory.refresh(try result("https://x/a.json", error: "HTTP 404"))
        let failed = try XCTUnwrap(hostedView(on: node))
        XCTAssertEqual(parent.subviews, [failed])
        factory.refresh(try result("https://x/a.json", document: #"{"_avplayer":{"loop":true},"url":"https://x/a.mp4"}"#))
        XCTAssertTrue(parent.subviews.isEmpty)
        XCTAssertTrue(node.childNodes.first?.geometry?.firstMaterial?.diffuse.contents is SKScene)
        XCTAssertEqual(factory.players.count, 1)
    }

    // The AR view forgets its codes only when the model says "Forget" was pressed. An empty glyph list is
    // not that: the list is also empty just after the detector has seen a code the model has not heard of yet.
    func testCodesAreForgottenOnlyWhenAsked() throws {
        let model = AppModel()
        let coordinator = ARGlyphView.Coordinator(model: model)
        coordinator.factory.parent = parent
        _ = coordinator.factory.node(physical: physical, result: try result("https://x/a.json"))
        XCTAssertTrue(model.foundGlyphs.isEmpty)

        coordinator.forgetIfAsked()
        XCTAssertEqual(parent.subviews.count, 1, "nothing was asked, so the glyph stays")

        model.forgetGlyphs()
        coordinator.forgetIfAsked()
        XCTAssertTrue(parent.subviews.isEmpty)
        // Acted on once: a glyph built afterwards is not dropped by the same forget.
        _ = coordinator.factory.node(physical: physical, result: try result("https://x/b.json"))
        coordinator.forgetIfAsked()
        XCTAssertEqual(parent.subviews.count, 1)
    }

    func testRefreshWithoutANodeDoesNothing() throws {
        factory.refresh(try result("https://x/a.json", document: #"{"_button":{},"text":"Hi"}"#))
        XCTAssertTrue(parent.subviews.isEmpty)
    }

    func testResetDropsViewsPlayersAndScenes() throws {
        _ = factory.node(physical: physical, result: try result("button", document: #"{"_button":{},"text":"Hi"}"#))
        _ = factory.node(physical: physical, result: try result("video", document: #"{"_avplayer":{"loop":true},"url":"https://x/a.mp4"}"#))
        let sceneNode = factory.node(physical: physical, result: try result("scene", document: #"{"_ui":{},"type":"Scene","actors":[]}"#))
        let player = try XCTUnwrap(factory.players["video"])
        let stage = try XCTUnwrap(factory.scenes["scene"]?.controller.stage)
        XCTAssertEqual(parent.subviews.count, 1)
        XCTAssertTrue(stage.parent === sceneNode)

        factory.reset()
        XCTAssertTrue(parent.subviews.isEmpty)
        XCTAssertTrue(factory.players.isEmpty)
        XCTAssertEqual(player.rate, 0)
        XCTAssertTrue(factory.scenes.isEmpty)
        XCTAssertNil(stage.parent)
        // A document arriving for a forgotten code builds nothing.
        factory.refresh(try result("button", document: #"{"_button":{},"text":"Late"}"#))
        XCTAssertTrue(parent.subviews.isEmpty)
    }
}
