//
//  BlueRouter.swift
//  QRX
//
//  Dispatches BLUE requests to handlers by method and path, with `{param}`
//  path segments. The QRX server routes (docs/BLUE.md) are installed by
//  `BlueGlyphService`.
//

import Foundation
import JsonUICore

public final class BlueRouter {
    public typealias Handler = (_ request: BlueRequest, _ params: [String: String]) -> BlueResponse

    private struct Route {
        let method: String
        let segments: [String]
        let handler: Handler
    }

    private var routes: [Route] = []
    private let lock = NSLock()

    public init() {}

    public func on(_ method: String, _ path: String, _ handler: @escaping Handler) {
        lock.lock(); defer { lock.unlock() }
        routes.append(Route(method: method.uppercased(), segments: BlueRouter.segments(path), handler: handler))
    }

    public func get(_ path: String, _ handler: @escaping Handler) { on("GET", path, handler) }
    public func post(_ path: String, _ handler: @escaping Handler) { on("POST", path, handler) }

    /// Routes `request`; 404 when no path matches, 405 when the path matches another method.
    public func handle(_ request: BlueRequest) -> BlueResponse {
        lock.lock(); let routes = self.routes; lock.unlock()
        let segments = BlueRouter.segments(request.path)
        var pathMatched = false
        for route in routes {
            guard let params = BlueRouter.match(route.segments, segments) else { continue }
            pathMatched = true
            guard route.method == request.method else { continue }
            return route.handler(request, params)
        }
        return pathMatched ? .error(405) : .error(404, "no route for \(request.path)")
    }

    /// Parses request text and routes it; malformed requests get a 400.
    public func handle(text: String) -> BlueResponse {
        do { return handle(try BlueRequest(text: text)) }
        catch { return .error(400, "\(error)") }
    }

    static func segments(_ path: String) -> [String] {
        path.split(separator: "/", omittingEmptySubsequences: true).map { String($0).removingPercentEncoding ?? String($0) }
    }

    static func match(_ pattern: [String], _ segments: [String]) -> [String: String]? {
        guard pattern.count == segments.count else { return nil }
        var params: [String: String] = [:]
        for (p, s) in zip(pattern, segments) {
            if p.hasPrefix("{") && p.hasSuffix("}") { params[String(p.dropFirst().dropLast())] = s }
            else if p != s { return nil }
        }
        return params
    }
}

/// Stores the glyph documents a device shares and serves the QRX routes.
public final class BlueGlyphService {
    public let router = BlueRouter()
    private var documents: [String: GlyphDocument] = [:]
    private let lock = NSLock()

    public init(documents: [String: GlyphDocument] = [:]) {
        self.documents = documents
        install()
    }

    public func share(_ document: GlyphDocument, as name: String) {
        lock.lock(); documents[name] = document; lock.unlock()
    }

    public func unshare(_ name: String) {
        lock.lock(); documents.removeValue(forKey: name); lock.unlock()
    }

    public var names: [String] { lock.lock(); defer { lock.unlock() }; return documents.keys.sorted() }

    public func document(named name: String) -> GlyphDocument? { lock.lock(); defer { lock.unlock() }; return documents[name] }

    private func install() {
        router.get("/ping") { _, _ in .ok("pong") }
        router.get("/glyphs") { [unowned self] _, _ in
            .json(JsonValue.array(self.names.map { .string($0) }).jsonString())
        }
        router.get("/glyph/{name}") { [unowned self] _, params in
            guard let name = params["name"], let document = self.document(named: name) else { return .error(404, "no glyph named \(params["name"] ?? "")") }
            return .json(document.jsonString())
        }
        router.post("/glyph/{name}") { [unowned self] request, params in
            guard let name = params["name"], let body = request.body else { return .error(400, "missing body") }
            do {
                self.share(try GlyphDocument(json: body), as: name)
                return BlueResponse(statusCode: 201)
            } catch {
                return .error(400, "\(error)")
            }
        }
    }
}
