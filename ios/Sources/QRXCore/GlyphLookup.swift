//
//  GlyphLookup.swift
//  QRX
//
//  Resolves a glyph payload into a document: inline bodies need no fetch,
//  `http(s)://` goes through a `GlyphFetcher` (URLSession by default), and
//  `blue://` goes through a `BlueTransport` supplied by the platform's
//  Bluetooth client.
//

import Foundation
import JsonUICore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum GlyphLookupError: Error, CustomStringConvertible {
    case noContent
    case bluetoothUnavailable
    case http(status: Int)
    case blue(status: Int, message: String?)
    case fragments(String)
    case tooManyFragments

    public var description: String {
        switch self {
        case .noContent: return "the code has no URL and no inline document"
        case .bluetoothUnavailable: return "Bluetooth is not available for blue:// glyphs"
        case .http(let status): return "HTTP \(status)"
        case .blue(let status, let message): return "BLUE \(status)\(message.map { ": " + $0 } ?? "")"
        case .fragments(let detail): return "fragments: \(detail)"
        case .tooManyFragments: return "too many fragment documents"
        }
    }
}

/// Fetches bytes for an `http(s)://` URL.
public protocol GlyphFetcher {
    func fetch(_ url: URL, completion: @escaping (Result<Data, Error>) -> Void)
}

/// Sends a BLUE request to a named peripheral (`*` for any) and returns its response.
public protocol BlueTransport: AnyObject {
    func send(_ request: BlueRequest, to device: String, completion: @escaping (Result<BlueResponse, Error>) -> Void)
}

public final class URLSessionFetcher: GlyphFetcher {
    public let session: URLSession
    public init(session: URLSession = .shared) { self.session = session }

    public func fetch(_ url: URL, completion: @escaping (Result<Data, Error>) -> Void) {
        session.dataTask(with: url) { data, response, error in
            if let error = error { completion(.failure(error)); return }
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                completion(.failure(GlyphLookupError.http(status: http.statusCode)))
                return
            }
            completion(.success(data ?? Data()))
        }.resume()
    }
}

public final class GlyphLookup {
    public let fetcher: GlyphFetcher
    public weak var blue: BlueTransport?
    private var cache: [String: GlyphDocument] = [:]
    private let lock = NSLock()

    public init(fetcher: GlyphFetcher = URLSessionFetcher(), blue: BlueTransport? = nil) {
        self.fetcher = fetcher
        self.blue = blue
    }

    /// How many fragment documents one glyph may pull in.
    public var maxFragmentDocuments = 16

    /// Resolves `barcode`. Results are cached by payload; `refresh` bypasses the cache.
    /// JSON fragments (`$ref`) in the document are fetched the same way the document was and resolved
    /// against its URL before it is parsed.
    public func lookup(_ barcode: GlyphBarcode, refresh: Bool = false, completion: @escaping (Result<GlyphDocument, Error>) -> Void) {
        if !refresh, let cached = cached(barcode.id) { completion(.success(cached)); return }
        let finish: (Result<GlyphDocument, Error>) -> Void = { [weak self] result in
            if case .success(let document) = result { self?.store(document, for: barcode.id) }
            completion(result)
        }
        if let inline = barcode.inlineDocument {
            resolve(json: .success(inline), base: nil, completion: finish)
            return
        }
        if let target = barcode.blueTarget {
            guard let blue = blue else { finish(.failure(GlyphLookupError.bluetoothUnavailable)); return }
            let base = URL(string: "blue://\(target.device)\(target.path.hasPrefix("/") ? "" : "/")\(target.path)")
            fetchBlue(target.path, device: target.device, blue: blue) { [weak self] result in
                self?.resolve(json: result, base: base, completion: finish)
            }
            return
        }
        guard let url = barcode.url else { finish(.failure(GlyphLookupError.noContent)); return }
        fetcher.fetch(url) { [weak self] result in
            self?.resolve(json: result.map { String(decoding: $0, as: UTF8.self) }, base: url, completion: finish)
        }
    }

    // MARK: - Fragments

    private func fetchBlue(_ path: String, device: String, blue: BlueTransport, completion: @escaping (Result<String, Error>) -> Void) {
        blue.send(.get(path), to: device) { result in
            completion(result.flatMap { response in
                guard response.isSuccess else { return .failure(GlyphLookupError.blue(status: response.statusCode, message: response.content)) }
                return .success(response.content ?? "")
            })
        }
    }

    /// Fetches one fragment document: `http(s)` through the fetcher, `blue://device/path` through the transport.
    private func fetchDocument(_ url: URL, completion: @escaping (Result<JsonValue, Error>) -> Void) {
        let parse: (Result<String, Error>) -> Void = { result in completion(result.flatMap { text in Result { try JsonValue.parse(text) } }) }
        if url.scheme == "blue" {
            guard let blue = blue else { completion(.failure(GlyphLookupError.bluetoothUnavailable)); return }
            fetchBlue(url.path, device: url.host ?? "*", blue: blue, completion: parse)
        } else {
            fetcher.fetch(url) { parse($0.map { String(decoding: $0, as: UTF8.self) }) }
        }
    }

    private func resolve(json: Result<String, Error>, base: URL?, completion: @escaping (Result<GlyphDocument, Error>) -> Void) {
        let value: JsonValue
        do { value = try JsonValue.parse(try json.get()) } catch { completion(.failure(error)); return }
        guard value.hasFragmentReferences else { completion(Result { try GlyphDocument(value: value) }); return }
        let resolver = JsonFragmentResolver()
        resolve(value, base: base, resolver: resolver, fetched: 0, completion: completion)
    }

    private func resolve(_ value: JsonValue, base: URL?, resolver: JsonFragmentResolver, fetched: Int, completion: @escaping (Result<GlyphDocument, Error>) -> Void) {
        let missing = resolver.externalReferences(in: value, base: base)
        if missing.isEmpty {
            completion(Result { try GlyphDocument(value: try resolver.resolve(value, base: base)) })
            return
        }
        guard fetched + missing.count <= maxFragmentDocuments else { completion(.failure(GlyphLookupError.tooManyFragments)); return }
        let group = DispatchGroup()
        var failure: Error?
        let lock = NSLock()
        for url in missing {
            group.enter()
            fetchDocument(url) { result in
                lock.lock()
                switch result {
                case .success(let document): resolver.register(document, for: url)
                case .failure(let error): failure = failure ?? GlyphLookupError.fragments("\(url.absoluteString): \(error)")
                }
                lock.unlock()
                group.leave()
            }
        }
        group.notify(queue: .global()) { [weak self] in
            guard let self = self else { return }
            if let failure = failure { completion(.failure(failure)); return }
            self.resolve(value, base: base, resolver: resolver, fetched: fetched + missing.count, completion: completion)
        }
    }

    public func cached(_ id: String) -> GlyphDocument? { lock.lock(); defer { lock.unlock() }; return cache[id] }

    func store(_ document: GlyphDocument, for id: String) { lock.lock(); cache[id] = document; lock.unlock() }

    public func clearCache() { lock.lock(); cache.removeAll(); lock.unlock() }
}

#if compiler(>=5.5)
extension GlyphLookup {
    @available(iOS 15, macOS 12, *)
    public func lookup(_ barcode: GlyphBarcode, refresh: Bool = false) async throws -> GlyphDocument {
        try await withCheckedThrowingContinuation { continuation in
            lookup(barcode, refresh: refresh) { continuation.resume(with: $0) }
        }
    }
}
#endif
