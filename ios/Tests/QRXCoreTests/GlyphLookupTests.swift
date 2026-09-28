import XCTest
@testable import QRXCore

final class FakeFetcher: GlyphFetcher {
    var responses: [URL: Result<Data, Error>] = [:]
    var calls = 0
    func fetch(_ url: URL, completion: @escaping (Result<Data, Error>) -> Void) {
        calls += 1
        completion(responses[url] ?? .failure(GlyphLookupError.http(status: 404)))
    }
}

final class FakeBlue: BlueTransport {
    var requests: [(BlueRequest, String)] = []
    var response = BlueResponse.json(#"{"_button":{},"text":"From BLE"}"#)
    func send(_ request: BlueRequest, to device: String, completion: @escaping (Result<BlueResponse, Error>) -> Void) {
        requests.append((request, device))
        completion(.success(response))
    }
}

final class GlyphLookupTests: XCTestCase {
    func testInlineHttpAndCache() throws {
        let fetcher = FakeFetcher()
        let url = URL(string: "https://x/glyph.json")!
        fetcher.responses[url] = .success(Data(#"{"_image":{},"url":"https://x/y.png"}"#.utf8))
        let lookup = GlyphLookup(fetcher: fetcher)

        var inline: GlyphDocument?
        lookup.lookup(GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":\"Inline\"}")) { inline = try? $0.get() }
        XCTAssertEqual(inline?.content, .button(text: "Inline", action: nil))
        XCTAssertEqual(fetcher.calls, 0)

        var fetched: GlyphDocument?
        let barcode = GlyphBarcode(payload: "https://x/glyph.json")
        lookup.lookup(barcode) { fetched = try? $0.get() }
        XCTAssertEqual(fetched?.content, .image(url: URL(string: "https://x/y.png")!))
        lookup.lookup(barcode) { fetched = try? $0.get() }
        XCTAssertEqual(fetcher.calls, 1, "second lookup is served from the cache")
        lookup.lookup(barcode, refresh: true) { fetched = try? $0.get() }
        XCTAssertEqual(fetcher.calls, 2)

        var failure: Error?
        lookup.lookup(GlyphBarcode(payload: "https://x/missing.json")) { if case .failure(let e) = $0 { failure = e } }
        XCTAssertEqual("\(failure!)", "HTTP 404")

        var empty: Error?
        lookup.lookup(GlyphBarcode.empty) { if case .failure(let e) = $0 { empty = e } }
        XCTAssertNotNil(empty)
    }

    func testBluetooth() {
        let blue = FakeBlue()
        let lookup = GlyphLookup(fetcher: FakeFetcher(), blue: blue)
        var document: GlyphDocument?
        lookup.lookup(GlyphBarcode(payload: "blue://Kiosk/glyph/menu")) { document = try? $0.get() }
        XCTAssertEqual(document?.content, .button(text: "From BLE", action: nil))
        XCTAssertEqual(blue.requests.count, 1)
        XCTAssertEqual(blue.requests[0].0.uri, "/glyph/menu")
        XCTAssertEqual(blue.requests[0].1, "Kiosk")

        blue.response = .error(404, "no glyph")
        var error: Error?
        lookup.lookup(GlyphBarcode(payload: "blue://*/glyph/x")) { if case .failure(let e) = $0 { error = e } }
        XCTAssertEqual("\(error!)", "BLUE 404: no glyph")

        var unavailable: Error?
        GlyphLookup(fetcher: FakeFetcher()).lookup(GlyphBarcode(payload: "blue://*/glyph/x")) { if case .failure(let e) = $0 { unavailable = e } }
        XCTAssertEqual("\(unavailable!)", "Bluetooth is not available for blue:// glyphs")
    }
}
