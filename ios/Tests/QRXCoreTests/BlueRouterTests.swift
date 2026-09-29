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

        service.share(.button("Shared"), as: "shared")
        XCTAssertEqual(service.names, ["menu", "shared"])
        service.unshare("menu")
        XCTAssertEqual(service.names, ["shared"])
    }
}
