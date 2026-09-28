import XCTest
import JsonUICore
@testable import QRXCore

final class BlueRouterTests: XCTestCase {
    func testRoutingAndParams() {
        let router = BlueRouter()
        router.get("/items/{id}") { _, params in .ok("item \(params["id"]!)") }
        router.post("/items/{id}") { request, params in .ok("saved \(params["id"]!): \(request.body ?? "")") }
        XCTAssertEqual(router.handle(.get("/items/42")).content, "item 42")
        XCTAssertEqual(router.handle(BlueRequest(method: "POST", uri: "/items/7", body: "x")).content, "saved 7: x")
        XCTAssertEqual(router.handle(.get("/nothing")).statusCode, 404)
        XCTAssertEqual(router.handle(BlueRequest(method: "DELETE", uri: "/items/1")).statusCode, 405)
        XCTAssertEqual(router.handle(text: "garbage").statusCode, 400)
        XCTAssertEqual(router.handle(text: "GET /items/9 BLUE/1.0\n\n").content, "item 9")
    }

    func testGlyphServiceRoutes() throws {
        let service = BlueGlyphService()
        var written: LedColor?
        service.setLedColor = { written = $0; return true }
        service.ledColor = { written }
        service.batteryLevel = { 87 }
        let router = service.router

        XCTAssertEqual(router.handle(.get("/ping")).content, "pong")
        XCTAssertEqual(router.handle(.get("/glyphs")).content, "[]")
        XCTAssertEqual(router.handle(.get("/glyph/menu")).statusCode, 404)

        let doc = #"{"_button":{},"text":"Menu"}"#
        XCTAssertEqual(router.handle(.post("/glyph/menu", json: doc)).statusCode, 201)
        XCTAssertEqual(router.handle(.get("/glyphs")).content, #"["menu"]"#)
        let fetched = router.handle(.get("/glyph/menu"))
        XCTAssertEqual(fetched.statusCode, 200)
        XCTAssertEqual(try GlyphDocument(json: fetched.content!).content, .button(text: "Menu", action: nil))
        XCTAssertEqual(router.handle(.post("/glyph/bad", json: "nope")).statusCode, 400)

        XCTAssertEqual(router.handle(.get("/led")).statusCode, 503)
        XCTAssertEqual(router.handle(.post("/led", json: #"{"r":255,"g":10,"b":0}"#)).content, #"{"b":0,"g":10,"r":255}"#)
        XCTAssertEqual(written, LedColor(red: 255, green: 10, blue: 0))
        XCTAssertEqual(router.handle(.get("/led")).content, #"{"b":0,"g":10,"r":255}"#)
        XCTAssertEqual(router.handle(.post("/led", json: #"{"r":1}"#)).statusCode, 400)
        XCTAssertEqual(router.handle(.get("/battery")).content, #"{"level":87}"#)

        service.share(.button("Shared"), as: "shared")
        XCTAssertEqual(service.names, ["menu", "shared"])
        service.unshare("menu")
        XCTAssertEqual(service.names, ["shared"])
    }

    func testLedColor() {
        XCTAssertEqual(LedColor(json: ["r": 300, "g": -5, "b": 12.6]), LedColor(red: 255, green: 0, blue: 13))
        XCTAssertEqual(LedColor(json: ["red": 1, "green": 2, "blue": 3]), LedColor(red: 1, green: 2, blue: 3))
        XCTAssertNil(LedColor(json: ["r": 1]))
        let color = LedColor(red: 9, green: 8, blue: 7)
        XCTAssertEqual(color.data(for: .red), Data([9]))
        XCTAssertEqual(color.data(for: .blue), Data([7]))
        XCTAssertEqual(LedColor.Channel.green.characteristicUUID, ParticleUUIDs.greenLED)
        XCTAssertEqual(color.json.jsonString(), #"{"b":7,"g":8,"r":9}"#)
    }
}
