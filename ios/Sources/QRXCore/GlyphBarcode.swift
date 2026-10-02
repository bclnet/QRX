//
//  GlyphBarcode.swift
//  QRX
//
//  The parsed payload of a QR code (see docs/GLYPH.md).
//

import Foundation

public struct GlyphBarcode: Equatable, Hashable {
    public static let empty = GlyphBarcode(payload: "")

    /// The raw QR payload.
    public let payload: String
    /// Headers other than `size:` and the URL, lower-cased names. Flag lines are stored as `name: "1"`.
    public let headers: [String: String]
    public let sizes: [GlyphSelector: GlyphSize]
    /// The `http(s)://` or `blue://` line of the payload, as written.
    public let location: String?
    /// `location` as a URL (nil for `blue://` names that are not valid URL hosts; use `blueTarget`).
    public let url: URL?
    /// Whether the same code may be shown several times.
    public let multi: Bool
    /// Text after the first blank line; the inline document when there is no URL.
    public let body: String?

    public init(payload: String) {
        self.payload = payload
        let lines = payload.components(separatedBy: "\n")
        var headers: [String: String] = [:]
        var sizes: [GlyphSelector: GlyphSize] = [:]
        var body: String?
        var sawHeader = false
        for (index, raw) in lines.enumerated() {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty {
                if !sawHeader { continue }
                body = lines[(index + 1)...].joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
                if body?.isEmpty == true { body = nil }
                break
            }
            sawHeader = true
            if GlyphBarcode.isURL(line) {
                headers["url"] = line
                continue
            }
            guard let separator = line.firstIndex(of: ":") else {
                // Flags are single tokens; anything else means the payload is plain text, not a glyph.
                guard GlyphBarcode.isToken(line) else {
                    headers = [:]; sizes = [:]; body = payload.trimmingCharacters(in: .whitespacesAndNewlines)
                    break
                }
                headers[line.lowercased()] = "1"
                continue
            }
            let name = line[..<separator].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
            if name == "size", let size = GlyphSize(string: value) {
                sizes[size.selector] = size
                continue
            }
            headers[name] = value
        }
        // A payload that is a single non-header line is text; keep it as the body.
        if headers.isEmpty && sizes.isEmpty && body == nil && !payload.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            body = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        // A body that is only a URL line is the location, written after a blank line: a URL is never a document.
        if headers["url"] == nil, let text = body, !text.contains(where: \.isNewline), GlyphBarcode.isURL(text) {
            headers["url"] = text
            body = nil
        }
        self.headers = headers
        self.sizes = sizes
        self.location = headers["url"]
        self.url = headers["url"].flatMap { URL(string: $0) }
        self.multi = headers["multi"] != nil
        self.body = body
    }

    /// Convenience initializer taking the raw payload.
    public init(string s: String) { self.init(payload: s) }

    static func isToken(_ line: String) -> Bool {
        !line.isEmpty && line.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" }
    }

    public static func isURL(_ line: String) -> Bool {
        let lower = line.lowercased()
        return lower.hasPrefix("http://") || lower.hasPrefix("https://") || lower.hasPrefix("blue://")
    }

    /// Whether the document is fetched over Bluetooth (`blue://device/path`).
    public var isBluetooth: Bool { location?.lowercased().hasPrefix("blue://") == true }

    /// The peripheral name and path of a `blue://` location. Device names may contain spaces.
    public var blueTarget: (device: String, path: String)? {
        guard isBluetooth, let location = location else { return nil }
        let rest = location.dropFirst("blue://".count)
        let slash = rest.firstIndex(of: "/") ?? rest.endIndex
        let device = String(rest[..<slash]).removingPercentEncoding ?? String(rest[..<slash])
        let path = slash < rest.endIndex ? String(rest[slash...]) : "/"
        return (device.isEmpty ? "*" : device, path)
    }

    /// The inline document body when the payload has no location.
    public var inlineDocument: String? { location == nil ? body : nil }

    /// The size to use for `selector`.
    public func size(for selector: GlyphSelector = .normal) -> GlyphSize { sizes.size(for: selector) }

    /// A stable identity for the code (its payload).
    public var id: String { payload }
}
