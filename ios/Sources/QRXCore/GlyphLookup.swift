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
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum GlyphLookupError: Error, CustomStringConvertible {
    case noContent
    case bluetoothUnavailable
    case http(status: Int)
    case blue(status: Int, message: String?)

    public var description: String {
        switch self {
        case .noContent: return "the code has no URL and no inline document"
        case .bluetoothUnavailable: return "Bluetooth is not available for blue:// glyphs"
        case .http(let status): return "HTTP \(status)"
        case .blue(let status, let message): return "BLUE \(status)\(message.map { ": " + $0 } ?? "")"
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

    /// Resolves `barcode`. Results are cached by payload; `refresh` bypasses the cache.
    public func lookup(_ barcode: GlyphBarcode, refresh: Bool = false, completion: @escaping (Result<GlyphDocument, Error>) -> Void) {
        if !refresh, let cached = cached(barcode.id) { completion(.success(cached)); return }
        let finish: (Result<GlyphDocument, Error>) -> Void = { [weak self] result in
            if case .success(let document) = result { self?.store(document, for: barcode.id) }
            completion(result)
        }
        if let inline = barcode.inlineDocument {
            finish(Result { try GlyphDocument(json: inline) })
            return
        }
        if let target = barcode.blueTarget {
            guard let blue = blue else { finish(.failure(GlyphLookupError.bluetoothUnavailable)); return }
            blue.send(.get(target.path), to: target.device) { result in
                finish(result.flatMap { response in
                    guard response.isSuccess else { return .failure(GlyphLookupError.blue(status: response.statusCode, message: response.content)) }
                    return Result { try GlyphDocument(json: response.content ?? "") }
                })
            }
            return
        }
        guard let url = barcode.url else { finish(.failure(GlyphLookupError.noContent)); return }
        fetcher.fetch(url) { result in
            finish(result.flatMap { data in Result { try GlyphDocument(data: data) } })
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
