import XCTest
@testable import QRXCore

final class BlueProtocolTests: XCTestCase {
    func testRequestRoundTrip() throws {
        let request = BlueRequest(method: "post", uri: "/glyph/menu?x=1&name=a%20b", headers: ["Content-Type": "application/json"], body: #"{"_button":{},"text":"hi"}"#)
        let text = request.text
        XCTAssertTrue(text.hasPrefix("POST /glyph/menu?x=1&name=a%20b BLUE/1.0\nContent-Length: 26\nContent-Type: application/json\n\n"))
        let parsed = try BlueRequest(text: text)
        XCTAssertEqual(parsed.method, "POST")
        XCTAssertEqual(parsed.uri, "/glyph/menu?x=1&name=a%20b")
        XCTAssertEqual(parsed.path, "/glyph/menu")
        XCTAssertEqual(parsed.query, ["x": "1", "name": "a b"])
        XCTAssertEqual(parsed.header("content-type"), "application/json")
        XCTAssertEqual(parsed.body, request.body)
    }

    func testRequestLineForms() throws {
        XCTAssertEqual(try BlueRequest(text: "GET /ping BLUE/1.0\n\n").uri, "/ping")
        XCTAssertEqual(try BlueRequest(text: "get /ping\r\n\r\n").method, "GET")
        XCTAssertEqual(try BlueRequest(text: "GET /a b BLUE/1.0\n").uri, "/a b")
        XCTAssertNil(try BlueRequest(text: "GET /ping BLUE/1.0\n\n").body)
        XCTAssertThrowsError(try BlueRequest(text: "")) { XCTAssertEqual($0 as? BlueProtocolError, .emptyMessage) }
        XCTAssertThrowsError(try BlueRequest(text: "GET\n")) { XCTAssertEqual($0 as? BlueProtocolError, .malformedRequestLine("GET")) }
        XCTAssertThrowsError(try BlueRequest(text: "GET / BLUE/2.0\n")) { XCTAssertEqual($0 as? BlueProtocolError, .unsupportedVersion("BLUE/2.0")) }
        XCTAssertThrowsError(try BlueRequest(text: "GET / BLUE/1.0\nnot a header\n")) { XCTAssertEqual($0 as? BlueProtocolError, .malformedHeader("not a header")) }
    }

    func testResponseRoundTrip() throws {
        let response = BlueResponse.json(#"{"ok":true}"#)
        XCTAssertEqual(response.text, "BLUE/1.0 200 OK\nContent-Length: 11\nContent-Type: application/json\n\n{\"ok\":true}")
        let parsed = try BlueResponse(text: response.text)
        XCTAssertEqual(parsed.statusCode, 200)
        XCTAssertEqual(parsed.statusDescription, "OK")
        XCTAssertEqual(parsed.content, #"{"ok":true}"#)
        XCTAssertTrue(parsed.isSuccess)
        var finished = BlueResponse(statusCode: 404, statusDescription: "Not Found")
        XCTAssertEqual(finished.finish(), "BLUE/1.0 404 Not Found\n\n")
        XCTAssertEqual(try BlueResponse(text: "BLUE/1.0 503\n\n").statusDescription, "Service Unavailable")
        XCTAssertThrowsError(try BlueResponse(text: "HTTP/1.1 200 OK\n\n")) { XCTAssertEqual($0 as? BlueProtocolError, .unsupportedVersion("HTTP/1.1")) }
        XCTAssertThrowsError(try BlueResponse(text: "BLUE/1.0 abc\n\n"))
        XCTAssertEqual(BlueResponse.error(400).content, "Bad Request")
    }

    func testContentLengthTruncatesTrailingBytes() {
        let message = BlueLineParser().parse("BLUE/1.0 200 OK\nContent-Length: 3\n\nabcXYZ")
        XCTAssertEqual(message.lines, ["BLUE/1.0 200 OK", "Content-Length: 3"])
        XCTAssertEqual(message.body, "abc")
    }
}
