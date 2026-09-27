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
        var result: String?
        for frame in frames { if let message = try assembler.append(frame) { result = message } }
        XCTAssertEqual(result, text)
        XCTAssertTrue(assembler.isIdle)
    }

    func testLargerMtuAndEmptyMessage() throws {
        let frames = BlueFramer.frames(for: "abc", mtu: 185)
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames[0].count, 7)
        XCTAssertEqual(try BlueAssembler().append(frames[0]), "abc")
        let empty = BlueFramer.frames(for: "", mtu: 23)
        XCTAssertEqual(try BlueAssembler().append(empty[0]), "")
    }

    func testBackToBackMessagesInOneChunk() throws {
        let a = BlueFramer.frames(for: "one", mtu: 100)[0]
        let b = BlueFramer.frames(for: "two", mtu: 100)[0]
        let assembler = BlueAssembler()
        XCTAssertEqual(try assembler.append(a + b.prefix(2)), "one")
        XCTAssertEqual(try assembler.append(b.suffix(from: 2)), "two")
    }

    func testRejectsAbsurdLength() {
        let assembler = BlueAssembler()
        XCTAssertThrowsError(try assembler.append(Data([0xff, 0xff, 0xff, 0x7f])))
        XCTAssertTrue(assembler.isIdle)
    }

    func testUnicode() throws {
        let text = "héllo ✓ 日本語"
        var result: String?
        let assembler = BlueAssembler()
        for frame in BlueFramer.frames(for: text, mtu: 23) { if let m = try assembler.append(frame) { result = m } }
        XCTAssertEqual(result, text)
    }
}
