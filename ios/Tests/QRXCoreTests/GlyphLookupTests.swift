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

    // The `url:` header takes any text, so the document's own scheme is checked before it is fetched.
    func testOnlyHttpAndBlueDocumentsAreFetched() {
        for location in ["file:///etc/hosts", "FILE:///etc/hosts", "ftp://x/a.json", "a.json"] {
            let fetcher = FakeFetcher()
            let lookup = GlyphLookup(fetcher: fetcher)
            var failure: Error?
            lookup.lookup(GlyphBarcode(payload: "url: \(location)")) { if case .failure(let e) = $0 { failure = e } }
            XCTAssertTrue("\(failure.map { "\($0)" } ?? "no failure")".contains("are not fetched"), location)
            XCTAssertEqual(fetcher.calls, 0, location)
        }
    }
}

final class GlyphFragmentTests: XCTestCase {
    func testFragmentsAreFetchedRelativeToTheDocument() throws {
        let fetcher = FakeFetcher()
        fetcher.responses[URL(string: "https://x/scenes/bush.json")!] = .success(Data(##"{"_ui":{"fragments":{"song":{"url":"x.wav"}}},"type":"Scene","actors":[{"id":"bush","body":{"$ref":"../bodies/box.json","sounds":{"song":{"$ref":"#song"}}},"mind":{"$ref":"minds/bush.json","budget":{"tokens":10}}}]}"##.utf8))
        fetcher.responses[URL(string: "https://x/bodies/box.json")!] = .success(Data(##"{"model":"https://x/box.glb","scale":0.1,"animations":{"sing":{"$ref":"clips.json#/sing"}}}"##.utf8))
        fetcher.responses[URL(string: "https://x/bodies/clips.json")!] = .success(Data(#"{"sing":{"clip":0,"speed":2}}"#.utf8))
        fetcher.responses[URL(string: "https://x/scenes/minds/bush.json")!] = .success(Data(#"{"persona":"a shrub","budget":{"tokens":99,"perTurn":50}}"#.utf8))
        let lookup = GlyphLookup(fetcher: fetcher)
        var document: GlyphDocument?
        var failure: Error?
        let done = expectation(description: "lookup")
        lookup.lookup(GlyphBarcode(payload: "https://x/scenes/bush.json")) {
            switch $0 { case .success(let d): document = d; case .failure(let e): failure = e }
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        XCTAssertNil(failure)
        guard case .ui(let json)? = document?.content else { return XCTFail("expected a ui document") }
        XCTAssertEqual(fetcher.calls, 4, "the document, two body fragments and the mind")
        let actor = json.root["actors"][0]
        XCTAssertEqual(actor["body"]["model"], "https://x/box.glb", "body fragment resolved relative to the document")
        XCTAssertEqual(actor["body"]["animations"]["sing"]["speed"], 2, "nested fragment resolved relative to the body file")
        XCTAssertEqual(actor["body"]["sounds"]["song"]["url"], "x.wav", "local fragment from _ui.fragments inside an override")
        XCTAssertEqual(actor["body"]["scale"], 0.1)
        XCTAssertEqual(actor["mind"]["persona"], "a shrub")
        XCTAssertEqual(actor["mind"]["budget"]["tokens"], 10, "override beside $ref wins")
        XCTAssertFalse(json.value.hasFragmentReferences)
    }

    func testMissingFragmentFails() {
        let fetcher = FakeFetcher()
        fetcher.responses[URL(string: "https://x/a.json")!] = .success(Data(##"{"_button":{},"text":{"$ref":"b.json#/text"}}"##.utf8))
        let lookup = GlyphLookup(fetcher: fetcher)
        var failure: Error?
        let done = expectation(description: "lookup")
        lookup.lookup(GlyphBarcode(payload: "https://x/a.json")) { if case .failure(let e) = $0 { failure = e }; done.fulfill() }
        wait(for: [done], timeout: 5)
        XCTAssertTrue("\(failure!)".contains("fragments: https://x/b.json"))
    }

    // A scanned code must not make the app read local files, or anything else that is not http(s) or blue.
    func testOnlyHttpAndBlueFragmentsAreFetched() {
        for ref in ["file:///etc/hosts#/text", "FILE:///etc/hosts", "ftp://x/a.json", "data:application/json,%7B%7D", "a.json#/text"] {
            let fetcher = FakeFetcher()
            fetcher.responses[URL(string: "https://x/a.json")!] = .success(Data("{\"_button\":{},\"text\":{\"$ref\":\"\(ref)\"}}".utf8))
            let lookup = GlyphLookup(fetcher: fetcher)
            let inline = GlyphBarcode(payload: "size: *2\n\n{\"_button\":{},\"text\":{\"$ref\":\"\(ref)\"}}")
            // Inline (no base URL) for every reference; through a fetched document for the absolute ones.
            for barcode in [inline] + (ref.contains(":") ? [GlyphBarcode(payload: "https://x/a.json")] : []) {
                var failure: Error?
                let before = fetcher.calls
                let done = expectation(description: "lookup \(ref)")
                lookup.lookup(barcode) { if case .failure(let e) = $0 { failure = e }; done.fulfill() }
                wait(for: [done], timeout: 5)
                XCTAssertTrue("\(failure.map { "\($0)" } ?? "no failure")".contains("references are not fetched"), ref)
                XCTAssertEqual(fetcher.calls - before, barcode.url == nil ? 0 : 1, "the fragment is never requested: \(ref)")
            }
        }
    }

    func testInlineDocumentsResolveLocalFragments() {
        let lookup = GlyphLookup(fetcher: FakeFetcher())
        var document: GlyphDocument?
        lookup.lookup(GlyphBarcode(payload: "size: *2\n\n{\"_ui\":{\"fragments\":{\"t\":\"Hi\"}},\"type\":\"Text\",\"text\":{\"$ref\":\"#t\"}}")) { document = try? $0.get() }
        guard case .ui(let json)? = document?.content else { return XCTFail() }
        XCTAssertEqual(json.root["text"], "Hi")
    }
}
