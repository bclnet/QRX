//
//  GlyphDocument.swift
//  QRX
//
//  A glyph document: JSON with one `_type` key (see docs/GLYPH.md). `_ui`
//  documents are JsonUI documents.
//

import Foundation
import JsonUICore

public enum GlyphDocumentError: Error, Equatable, CustomStringConvertible {
    case notAnObject
    case missingType
    case missing(String)
    case invalid(String)

    public var description: String {
        switch self {
        case .notAnObject: return "glyph document must be a JSON object"
        case .missingType: return "glyph document has no _type key"
        case .missing(let field): return "glyph document is missing \"\(field)\""
        case .invalid(let message): return "invalid glyph document: \(message)"
        }
    }
}

public enum GlyphContent: Equatable {
    case image(url: URL)
    case video(url: URL, loop: Bool)
    case web(url: URL)
    case button(text: String, action: JsonAction?)
    case ui(JsonDocument)
    case unknown(type: String)

    public var typeName: String {
        switch self {
        case .image: return "image"
        case .video: return "avplayer"
        case .web: return "web"
        case .button: return "button"
        case .ui: return "ui"
        case .unknown(let type): return type
        }
    }
}

public struct GlyphDocument: Equatable {
    public let content: GlyphContent
    /// The whole document.
    public let value: JsonValue

    public init(content: GlyphContent, value: JsonValue) {
        self.content = content
        self.value = value
    }

    public init(json: String) throws {
        try self.init(value: try JsonValue.parse(json))
    }

    public init(data: Data) throws {
        try self.init(value: try JsonValue.parse(data))
    }

    public init(value: JsonValue) throws {
        guard case .object(let object) = value else { throw GlyphDocumentError.notAnObject }
        guard let typeKey = object.keys.sorted().first(where: { $0.hasPrefix("_") }) else { throw GlyphDocumentError.missingType }
        let options = object[typeKey] ?? .null
        func url(_ key: String = "url") throws -> URL {
            guard let text = object[key]?.stringValue else { throw GlyphDocumentError.missing(key) }
            guard let url = URL(string: text) else { throw GlyphDocumentError.invalid("\(key) is not a URL") }
            return url
        }
        switch typeKey.lowercased() {
        case "_image":
            content = .image(url: try url())
        case "_avplayer", "_video":
            content = .video(url: try url(), loop: options["loop"].boolValue ?? object["loop"]?.boolValue ?? false)
        case "_web":
            content = .web(url: try url())
        case "_button":
            guard let text = object["text"]?.stringValue else { throw GlyphDocumentError.missing("text") }
            content = .button(text: text, action: object["action"].flatMap { JsonAction($0) })
        case "_ui":
            content = .ui(try JsonDocument(value: value))
        default:
            content = .unknown(type: String(typeKey.dropFirst()))
        }
        self.value = value
    }

    /// Builds a `_button` document.
    public static func button(_ text: String, action: JsonAction? = nil) -> GlyphDocument {
        var object: [String: JsonValue] = ["_button": .object([:]), "text": .string(text)]
        if let action = action { object["action"] = action.value }
        return GlyphDocument(content: .button(text: text, action: action), value: .object(object))
    }

    /// Wraps a JsonUI document as a `_ui` glyph.
    public static func ui(_ document: JsonDocument) -> GlyphDocument {
        GlyphDocument(content: .ui(document), value: document.value)
    }

    public func jsonString(pretty: Bool = false) -> String { value.jsonString(pretty: pretty) }
}
