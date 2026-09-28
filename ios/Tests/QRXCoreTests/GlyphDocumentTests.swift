import XCTest
import JsonUICore
@testable import QRXCore

final class GlyphDocumentTests: XCTestCase {
    static var examples: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("examples")
    }

    func testTypes() throws {
        XCTAssertEqual(try GlyphDocument(json: #"{"_image":{},"url":"https://x/y.png"}"#).content, .image(url: URL(string: "https://x/y.png")!))
        XCTAssertEqual(try GlyphDocument(json: #"{"_avplayer":{"loop":true},"url":"https://x/y.mp4"}"#).content, .video(url: URL(string: "https://x/y.mp4")!, loop: true))
        XCTAssertEqual(try GlyphDocument(json: #"{"_avplayer":{},"url":"https://x/y.mp4"}"#).content, .video(url: URL(string: "https://x/y.mp4")!, loop: false))
        XCTAssertEqual(try GlyphDocument(json: #"{"_web":{},"url":"https://x"}"#).content, .web(url: URL(string: "https://x")!))
        XCTAssertEqual(try GlyphDocument(json: #"{"_button":{},"text":"Tap"}"#).content, .button(text: "Tap", action: nil))
        XCTAssertEqual(try GlyphDocument(json: #"{"_button":{},"text":"Tap","action":"toast"}"#).content, .button(text: "Tap", action: .host(name: "toast", args: [:])))
        XCTAssertEqual(try GlyphDocument(json: #"{"_hologram":{},"x":1}"#).content, .unknown(type: "hologram"))
        if case .ui(let document) = try GlyphDocument(json: #"{"_ui":{"state":{"a":1}},"type":"Text","text":"$a"}"#).content {
            XCTAssertEqual(document.header.state["a"], 1)
            XCTAssertEqual(document.root.kind, .text)
        } else {
            XCTFail("expected a ui glyph")
        }
    }

    func testErrors() {
        XCTAssertThrowsError(try GlyphDocument(json: "[1]")) { XCTAssertEqual($0 as? GlyphDocumentError, .notAnObject) }
        XCTAssertThrowsError(try GlyphDocument(json: #"{"url":"x"}"#)) { XCTAssertEqual($0 as? GlyphDocumentError, .missingType) }
        XCTAssertThrowsError(try GlyphDocument(json: #"{"_image":{}}"#)) { XCTAssertEqual($0 as? GlyphDocumentError, .missing("url")) }
        XCTAssertThrowsError(try GlyphDocument(json: #"{"_button":{}}"#)) { XCTAssertEqual($0 as? GlyphDocumentError, .missing("text")) }
        XCTAssertThrowsError(try GlyphDocument(json: "not json"))
    }

    func testExamplesParse() throws {
        let files = try FileManager.default.contentsOfDirectory(atPath: GlyphDocumentTests.examples.path).filter { $0.hasSuffix(".json") }
        XCTAssertGreaterThanOrEqual(files.count, 6)
        var types: Set<String> = []
        for file in files {
            let document = try GlyphDocument(data: try Data(contentsOf: GlyphDocumentTests.examples.appendingPathComponent(file)))
            types.insert(document.content.typeName)
            if case .ui(let doc) = document.content {
                XCTAssertTrue(JsonRuntime(document: doc).scriptErrors.isEmpty, file)
            }
        }
        XCTAssertEqual(types, ["image", "avplayer", "web", "button", "ui"])
    }

    func testBuilders() throws {
        let button = GlyphDocument.button("Go", action: .host(name: "open", args: ["url": "https://x"]))
        XCTAssertEqual(try GlyphDocument(json: button.jsonString()), button)
        let ui = GlyphDocument.ui(JsonDocument(header: JsonUIHeader(state: ["n": 1]), root: .text("$n")))
        XCTAssertEqual(try GlyphDocument(json: ui.jsonString()).content, ui.content)
    }
}
