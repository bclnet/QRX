//
//  GlyphSize.swift
//  QRX
//
//  The `size:` header of a glyph payload. Uses Double so
//  it builds on Linux.
//
//      <width>x<height>[:selector] | <value> | ~
//      width  := [*] number [anchor [offset]]
//

import Foundation

public enum GlyphSelector: String, CaseIterable, Codable {
    case fixed, normal, focus, active

    public var description: String { ":" + rawValue }

    public init(string s: String) {
        switch s.lowercased() {
        case ":fixed", "fixed": self = .fixed
        case ":focus", "focus": self = .focus
        case ":active", "active": self = .active
        default: self = .normal
        }
    }

    fileprivate init(parse s: String) {
        guard let idx = s.lastIndex(of: ":") else { self = .normal; return }
        self = GlyphSelector(string: String(s[idx...]))
    }
}

public struct GlyphSize: Equatable, Hashable, CustomStringConvertible, Codable {
    public enum Anchor: String, Codable {
        /// left / top
        case start
        case center
        /// right / bottom
        case end

        init?(string s: String) {
            switch s.lowercased() {
            case "l", "t": self = .start
            case "r", "b": self = .end
            case "c": self = .center
            default: return nil
            }
        }

        var widthLetter: String { self == .start ? "l" : self == .end ? "r" : "c" }
        var heightLetter: String { self == .start ? "t" : self == .end ? "b" : "c" }
    }

    /// One dimension of a size.
    public struct Dimension: Equatable, Hashable, Codable {
        public static let unit = Dimension(multiple: true, value: 1, anchor: .center, offset: 0)

        /// When true `value` is a multiple of the code's size, otherwise it is in code units.
        public let multiple: Bool
        public let value: Double
        public let anchor: Anchor
        public let offset: Double

        public init(multiple: Bool, value: Double, anchor: Anchor = .center, offset: Double = 0) {
            self.multiple = multiple
            self.value = value
            self.anchor = anchor
            self.offset = offset
        }

        /// Parses `[*]value[anchor[offset]]`. A trailing `:selector` is ignored.
        public init?(string s: String) {
            let text = s.lastIndex(of: ":").map { String(s[..<$0]) } ?? s
            var rest = Substring(text)
            multiple = rest.hasPrefix("*")
            if multiple { rest = rest.dropFirst() }
            let numberEnd = rest.firstIndex { !"+-.0123456789".contains($0) } ?? rest.endIndex
            guard let value = Double(rest[..<numberEnd]) else { return nil }
            self.value = value
            guard numberEnd < rest.endIndex else { anchor = .center; offset = 0; return }
            guard let anchor = Anchor(string: String(rest[numberEnd])) else { return nil }
            self.anchor = anchor
            let offsetText = rest[rest.index(after: numberEnd)...]
            if offsetText.isEmpty { offset = 0 }
            else if let offset = Double(offsetText) { self.offset = offset }
            else { return nil }
        }

        func description(letter: (Anchor) -> String) -> String {
            var b = multiple ? "*" : ""
            b += GlyphSize.clean(value)
            if anchor != .center || offset != 0 { b += letter(anchor) }
            if offset != 0 { b += GlyphSize.clean(offset) }
            return b
        }

        /// Resolves the dimension against the code's size in some unit: (size, offset from the code's centre).
        public func resolve(code: Double) -> (size: Double, offset: Double) {
            let size = multiple ? value * code : value
            let anchored: Double
            switch anchor {
            case .center: anchored = 0
            case .start: anchored = -(code - size) / 2
            case .end: anchored = (code - size) / 2
            }
            return (size, anchored + offset)
        }
    }

    public static let zero = GlyphSize(selector: .normal, width: .unit, height: .unit)

    public let selector: GlyphSelector
    public let width: Dimension
    public let height: Dimension

    public init(selector: GlyphSelector = .normal, width: Dimension, height: Dimension) {
        self.selector = selector
        self.width = width
        self.height = height
    }

    public init?(string s: String) {
        let text = s.trimmingCharacters(in: .whitespaces)
        if text.isEmpty || text == "~" { self = .zero; return }
        // Take the selector off before splitting: ":fixed" has an "x" of its own.
        let body = text.lastIndex(of: ":").map { text[..<$0] } ?? Substring(text)
        let parts = body.split(maxSplits: 1, omittingEmptySubsequences: false, whereSeparator: { $0 == "x" || $0 == "X" }).map(String.init)
        guard let first = parts.first, let width = Dimension(string: first) else { return nil }
        guard let height = Dimension(string: parts.count > 1 ? parts[1] : first) else { return nil }
        selector = GlyphSelector(parse: text)
        self.width = width
        self.height = height
    }

    public var description: String {
        if self == .zero { return "~" }
        var b = width.description(letter: { $0.widthLetter }) + "x" + height.description(letter: { $0.heightLetter })
        if selector != .normal { b += selector.description }
        return b
    }

    static func clean(_ v: Double) -> String {
        // Not through Int: a whole value can be larger than Int.max.
        v.truncatingRemainder(dividingBy: 1) == 0 ? String(format: "%.0f", v) : String(v)
    }

    // MARK: - Codable (as the string form)

    public init(from decoder: Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let size = GlyphSize(string: text) else {
            throw DecodingError.dataCorrupted(DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "invalid glyph size \(text)"))
        }
        self = size
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(description)
    }
}

extension Dictionary where Key == GlyphSelector, Value == GlyphSize {
    /// The size for `selector`, falling back to `.normal`, then the default size.
    public func size(for selector: GlyphSelector) -> GlyphSize {
        self[selector] ?? self[.normal] ?? .zero
    }
}
