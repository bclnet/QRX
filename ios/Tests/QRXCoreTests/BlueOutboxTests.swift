import XCTest
@testable import QRXCore

final class BlueOutboxTests: XCTestCase {
    private func frames(_ text: String) -> [Data] { text.map { Data(String($0).utf8) } }

    // Two clients ask at once: each gets its own response, whole and in order, and nothing of the other's.
    func testEachClientGetsOnlyItsOwnChunks() {
        var outbox = BlueOutbox<String>()
        outbox.enqueue(frames("abc"), for: "one")
        outbox.enqueue(frames("xyz"), for: "two")
        var sent: [String: String] = [:]
        outbox.flush(to: ["one", "two"]) { client, frame in sent[client, default: ""] += String(decoding: frame, as: UTF8.self); return true }
        XCTAssertEqual(sent, ["one": "abc", "two": "xyz"])
        XCTAssertTrue(outbox.isEmpty)
    }

    func testAFullTransportKeepsTheRestForLater() {
        var outbox = BlueOutbox<String>()
        outbox.enqueue(frames("abcd"), for: "one")
        var sent = ""
        var room = 2
        let send: (String, Data) -> Bool = { _, frame in
            guard room > 0 else { return false }
            room -= 1
            sent += String(decoding: frame, as: UTF8.self)
            return true
        }
        outbox.flush(to: ["one"], send: send)
        XCTAssertEqual(sent, "ab")
        XCTAssertFalse(outbox.isEmpty)
        room = 10
        outbox.flush(to: ["one"], send: send)
        XCTAssertEqual(sent, "abcd", "nothing lost or repeated")
        XCTAssertTrue(outbox.isEmpty)
    }

    // A client that has not subscribed reads its chunks; they are not notified to anyone, and it never reads another's.
    func testUnsubscribedClientsReadTheirOwnChunks() {
        var outbox = BlueOutbox<String>()
        outbox.enqueue(frames("ab"), for: "reader")
        outbox.enqueue(frames("x"), for: "subscriber")
        var notified = ""
        outbox.flush(to: ["subscriber"]) { _, frame in notified += String(decoding: frame, as: UTF8.self); return true }
        XCTAssertEqual(notified, "x")
        XCTAssertNil(outbox.next(for: "subscriber"))
        XCTAssertEqual(outbox.next(for: "reader"), Data("a".utf8))
        XCTAssertEqual(outbox.next(for: "reader"), Data("b".utf8))
        XCTAssertNil(outbox.next(for: "reader"))
    }

    func testRemoveForgetsAClient() {
        var outbox = BlueOutbox<String>()
        outbox.enqueue(frames("ab"), for: "gone")
        outbox.enqueue(frames("x"), for: "here")
        outbox.remove("gone")
        XCTAssertNil(outbox.next(for: "gone"))
        XCTAssertEqual(outbox.next(for: "here"), Data("x".utf8))
        XCTAssertTrue(outbox.isEmpty)
    }
}
