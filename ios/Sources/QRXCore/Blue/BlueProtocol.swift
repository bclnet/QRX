//
//  BlueProtocol.swift
//  QRX
//
//  BLUE/1.0: an HTTP-like text protocol carried over Bluetooth LE (see
//  docs/BLUE.md): the `BlueRequest` / `BlueResponse` message grammar.
//

import Foundation

public enum BlueProtocolError: Error, Equatable, CustomStringConvertible {
    case emptyMessage
    case malformedRequestLine(String)
    case malformedStatusLine(String)
    case malformedHeader(String)
    case unsupportedVersion(String)

    public var description: String {
        switch self {
        case .emptyMessage: return "BLUE: empty message"
        case .malformedRequestLine(let line): return "BLUE: malformed request line \"\(line)\""
        case .malformedStatusLine(let line): return "BLUE: malformed status line \"\(line)\""
        case .malformedHeader(let line): return "BLUE: malformed header \"\(line)\""
        case .unsupportedVersion(let version): return "BLUE: unsupported version \(version)"
        }
    }
}

public enum Blue {
    public static let version = "BLUE/1.0"
    public static let contentLength = "Content-Length"
    public static let contentType = "Content-Type"
    public static let json = "application/json"
    public static let text = "text/plain"
}

/// Splits a message into header lines and body.
public struct BlueLineParser {
    public struct Message: Equatable {
        public var lines: [String]
        public var body: String?
    }

    public init() {}

    /// Header lines end at the first empty line; the rest is the body (honouring `Content-Length` when present).
    public func parse(_ text: String) -> Message {
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        var lines: [String] = []
        var index = normalized.startIndex
        var body: String?
        while index < normalized.endIndex {
            let lineEnd = normalized[index...].firstIndex(of: "\n") ?? normalized.endIndex
            let line = String(normalized[index..<lineEnd])
            index = lineEnd < normalized.endIndex ? normalized.index(after: lineEnd) : lineEnd
            if line.isEmpty {
                if index < normalized.endIndex { body = String(normalized[index...]) }
                break
            }
            lines.append(line)
        }
        if let declared = lines.compactMap({ BlueLineParser.header($0) }).first(where: { $0.name.lowercased() == Blue.contentLength.lowercased() }),
           let length = Int(declared.value), let text = body {
            let bytes = Array(text.utf8)
            if bytes.count > length { body = String(decoding: bytes[..<length], as: UTF8.self) }
        }
        return Message(lines: lines, body: body)
    }

    static func header(_ line: String) -> (name: String, value: String)? {
        guard let separator = line.firstIndex(of: ":") else { return nil }
        let name = line[..<separator].trimmingCharacters(in: .whitespaces)
        let value = line[line.index(after: separator)...].trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? nil : (name, value)
    }

    static func headers(_ lines: ArraySlice<String>) throws -> [String: String] {
        var headers: [String: String] = [:]
        for line in lines {
            guard let (name, value) = header(line) else { throw BlueProtocolError.malformedHeader(line) }
            headers[name] = value
        }
        return headers
    }
}

public struct BlueRequest: Equatable {
    public var method: String
    public var uri: String
    public var headers: [String: String]
    public var body: String?

    public init(method: String = "GET", uri: String, headers: [String: String] = [:], body: String? = nil) {
        self.method = method.uppercased()
        self.uri = uri
        self.headers = headers
        self.body = body
    }

    public static func get(_ uri: String) -> BlueRequest { BlueRequest(method: "GET", uri: uri) }

    public static func post(_ uri: String, json: String) -> BlueRequest {
        BlueRequest(method: "POST", uri: uri, headers: [Blue.contentType: Blue.json], body: json)
    }

    /// Parses `METHOD uri BLUE/1.0`, headers and body.
    public init(text: String) throws {
        let message = BlueLineParser().parse(text)
        guard let first = message.lines.first else { throw BlueProtocolError.emptyMessage }
        let parts = first.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        guard parts.count >= 2 else { throw BlueProtocolError.malformedRequestLine(first) }
        if parts.count >= 3, parts.last!.uppercased().hasPrefix("BLUE/"), parts.last!.uppercased() != Blue.version {
            throw BlueProtocolError.unsupportedVersion(parts.last!)
        }
        method = parts[0].uppercased()
        uri = parts.count >= 3 && parts.last!.uppercased().hasPrefix("BLUE/") ? parts[1..<(parts.count - 1)].joined(separator: " ") : parts[1...].joined(separator: " ")
        headers = try BlueLineParser.headers(message.lines.dropFirst())
        body = message.body
    }

    /// The path part of the URI, without query.
    public var path: String { uri.split(separator: "?", maxSplits: 1).first.map(String.init) ?? uri }

    public var query: [String: String] {
        guard let q = uri.split(separator: "?", maxSplits: 1).dropFirst().first else { return [:] }
        var result: [String: String] = [:]
        for pair in q.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map { String($0).removingPercentEncoding ?? String($0) }
            result[kv[0]] = kv.count > 1 ? kv[1] : ""
        }
        return result
    }

    public func header(_ name: String) -> String? {
        headers.first { $0.key.lowercased() == name.lowercased() }?.value
    }

    /// Serializes the request; `Content-Length` is added when there is a body.
    public var text: String {
        var lines = ["\(method) \(uri) \(Blue.version)"]
        var headers = self.headers
        if let body = body { headers[Blue.contentLength] = String(body.utf8.count) }
        for key in headers.keys.sorted() { lines.append("\(key): \(headers[key]!)") }
        lines.append("")
        return lines.joined(separator: "\n") + "\n" + (body ?? "")
    }
}

public struct BlueResponse: Equatable {
    public var statusCode: Int
    public var statusDescription: String
    public var headers: [String: String]
    public var content: String?

    public init(statusCode: Int = 200, statusDescription: String? = nil, headers: [String: String] = [:], content: String? = nil) {
        self.statusCode = statusCode
        self.statusDescription = statusDescription ?? BlueResponse.reason(for: statusCode)
        self.headers = headers
        self.content = content
    }

    public static func ok(_ content: String, type: String = Blue.text) -> BlueResponse {
        BlueResponse(statusCode: 200, headers: [Blue.contentType: type], content: content)
    }

    public static func json(_ json: String, statusCode: Int = 200) -> BlueResponse {
        BlueResponse(statusCode: statusCode, headers: [Blue.contentType: Blue.json], content: json)
    }

    public static func error(_ statusCode: Int, _ message: String? = nil) -> BlueResponse {
        BlueResponse(statusCode: statusCode, headers: [Blue.contentType: Blue.text], content: message ?? reason(for: statusCode))
    }

    public static func reason(for status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 201: return "Created"
        case 204: return "No Content"
        case 400: return "Bad Request"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 500: return "Internal Server Error"
        case 503: return "Service Unavailable"
        default: return ""
        }
    }

    /// Parses `BLUE/1.0 status description`, headers and content.
    public init(text: String) throws {
        let message = BlueLineParser().parse(text)
        guard let first = message.lines.first else { throw BlueProtocolError.emptyMessage }
        let parts = first.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: true).map(String.init)
        guard parts.count >= 2, let status = Int(parts[1]) else { throw BlueProtocolError.malformedStatusLine(first) }
        guard parts[0].uppercased() == Blue.version else { throw BlueProtocolError.unsupportedVersion(parts[0]) }
        statusCode = status
        statusDescription = parts.count > 2 ? parts[2] : BlueResponse.reason(for: status)
        headers = try BlueLineParser.headers(message.lines.dropFirst())
        content = message.body
    }

    public var isSuccess: Bool { (200..<300).contains(statusCode) }

    public func header(_ name: String) -> String? {
        headers.first { $0.key.lowercased() == name.lowercased() }?.value
    }

    /// Serializes the response.
    public var text: String {
        var lines = ["\(Blue.version) \(statusCode) \(statusDescription)"]
        var headers = self.headers
        if let content = content { headers[Blue.contentLength] = String(content.utf8.count) }
        for key in headers.keys.sorted() { lines.append("\(key): \(headers[key]!)") }
        lines.append("")
        return lines.joined(separator: "\n") + "\n" + (content ?? "")
    }

    public mutating func finish() -> String { text }
}
