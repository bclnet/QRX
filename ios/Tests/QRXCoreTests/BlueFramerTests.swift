import XCTest
@testable import QRXCore

final class BlueFramerTests: XCTestCase {
    func testFramesAndReassembly() throws {
        let text = String(repeating: "Hello BLUE! ", count: 20)
        let frames = BlueFramer.frames(for: text, mtu: 23)
        XCTAssertEqual(frames.count, Int(ceil(Double(text.utf8.count + 4) / 20)))
        XCTAssertTrue(frames.allSatisfy { $0.count <= 20 })
        XCTAssertEqual(frames[0].prefix(4), Data([UInt8(text.utf8.count & 0xff), UInt8(text.utf8.count >> 8), 0, 0]))
        let assembler = BlueAssembler()
        var result: [String] = []
        for frame in frames { result += try assembler.append(frame) }
        XCTAssertEqual(result, [text])
        XCTAssertTrue(assembler.isIdle)
    }

    func testLargerMtuAndEmptyMessage() throws {
        let frames = BlueFramer.frames(for: "abc", mtu: 185)
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames[0].count, 7)
        XCTAssertEqual(try BlueAssembler().append(frames[0]), ["abc"])
        let empty = BlueFramer.frames(for: "", mtu: 23)
        XCTAssertEqual(try BlueAssembler().append(empty[0]), [""])
    }

    func testBackToBackMessagesInOneChunk() throws {
        let a = BlueFramer.frames(for: "one", mtu: 100)[0]
        let b = BlueFramer.frames(for: "two", mtu: 100)[0]
        let assembler = BlueAssembler()
        XCTAssertEqual(try assembler.append(a + b.prefix(2)), ["one"])
        XCTAssertEqual(try assembler.append(b.suffix(from: 2)), ["two"])
    }

    // A chunk can hold more than one whole message; none of them may be dropped.
    func testWholeMessagesInOneChunk() throws {
        let chunk = ["one", "", "three"].reduce(Data()) { $0 + BlueFramer.frames(for: $1, mtu: 100)[0] }
        let assembler = BlueAssembler()
        XCTAssertEqual(try assembler.append(chunk), ["one", "", "three"])
        XCTAssertTrue(assembler.isIdle)
        // The tail of one message, a whole one, and the start of the next.
        let a = BlueFramer.frames(for: "alpha", mtu: 100)[0], b = BlueFramer.frames(for: "beta", mtu: 100)[0], c = BlueFramer.frames(for: "gamma", mtu: 100)[0]
        XCTAssertEqual(try assembler.append(a.prefix(3)), [])
        XCTAssertEqual(try assembler.append(a.suffix(from: 3) + b + c.prefix(5)), ["alpha", "beta"])
        XCTAssertEqual(try assembler.append(c.suffix(from: 5)), ["gamma"])
        XCTAssertTrue(assembler.isIdle)
    }

    func testRejectsAbsurdLength() {
        let assembler = BlueAssembler()
        XCTAssertThrowsError(try assembler.append(Data([0xff, 0xff, 0xff, 0x7f])))
        XCTAssertTrue(assembler.isIdle)
    }

    func testUnicode() throws {
        let text = "héllo ✓ 日本語"
        var result: [String] = []
        let assembler = BlueAssembler()
        for frame in BlueFramer.frames(for: text, mtu: 23) { result += try assembler.append(frame) }
        XCTAssertEqual(result, [text])
    }
}
