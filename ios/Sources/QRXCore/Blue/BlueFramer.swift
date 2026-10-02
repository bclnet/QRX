//
//  BlueFramer.swift
//  QRX
//
//  Length-prefixed chunk framing for BLUE messages over GATT characteristics.
//  A message is `UInt32 little-endian length` + UTF-8 bytes, split into
//  chunks of at most `chunkSize` bytes (MTU - 3).
//

import Foundation

public enum BlueFramer {
    public static let defaultMTU = 23
    public static let headerSize = 4
    /// The largest message accepted by the assembler (protects against bad length prefixes).
    public static let maxMessageSize = 4 * 1024 * 1024

    public static func chunkSize(mtu: Int) -> Int { max(1, mtu - 3) }

    /// Splits `text` into chunks ready to be written or notified.
    public static func frames(for text: String, mtu: Int = defaultMTU) -> [Data] {
        frames(for: Data(text.utf8), chunkSize: chunkSize(mtu: mtu))
    }

    public static func frames(for payload: Data, chunkSize: Int) -> [Data] {
        var message = Data(capacity: headerSize + payload.count)
        var length = UInt32(payload.count).littleEndian
        withUnsafeBytes(of: &length) { message.append(contentsOf: $0) }
        message.append(payload)
        var frames: [Data] = []
        var offset = 0
        let size = max(1, chunkSize)
        while offset < message.count {
            let end = min(offset + size, message.count)
            frames.append(message.subdata(in: offset..<end))
            offset = end
        }
        return frames
    }
}

/// Reassembles chunks into messages. One instance per connection and direction.
public final class BlueAssembler {
    private var buffer = Data()
    private var expected: Int?

    public init() {}

    public var isIdle: Bool { buffer.isEmpty && expected == nil }

    /// Appends a chunk; returns the messages it completed, in order. Usually none or one, but a chunk
    /// can carry the end of one message and all of the next.
    @discardableResult
    public func append(_ chunk: Data) throws -> [String] {
        buffer.append(chunk)
        var messages: [String] = []
        while true {
            if expected == nil, buffer.count >= BlueFramer.headerSize {
                let length = buffer.prefix(BlueFramer.headerSize).withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }
                let value = Int(UInt32(littleEndian: length))
                guard value <= BlueFramer.maxMessageSize else {
                    reset()
                    throw BlueAssemblerError.messageTooLarge(value)
                }
                expected = value
            }
            guard let length = expected, buffer.count >= BlueFramer.headerSize + length else { return messages }
            let end = BlueFramer.headerSize + length
            messages.append(String(decoding: buffer.subdata(in: BlueFramer.headerSize..<end), as: UTF8.self))
            buffer = buffer.subdata(in: end..<buffer.count)
            expected = nil
        }
    }

    public func reset() {
        buffer.removeAll()
        expected = nil
    }
}

public enum BlueAssemblerError: Error, Equatable {
    case messageTooLarge(Int)
}
