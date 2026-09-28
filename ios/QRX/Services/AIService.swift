//
//  AIService.swift
//  QRX
//
//  The app's TokenX server: providers and keys entered by the user in the
//  settings panel, the usage ledger, and the JsonMind provider handed to the
//  scenes so actors' minds can think. Keys live encrypted in SQLite with the
//  cipher key in the Keychain.
//

import Foundation
import Combine
import TokenX
import TokenXApple
import JsonMindTokenX

@MainActor
final class AIService: ObservableObject {
    let server: TokenServer
    /// The provider scenes attach to their actors' minds.
    let provider: TokenXMindProvider

    @Published private(set) var settings: Settings
    @Published private(set) var configured: [ProviderKind] = []
    @Published private(set) var usageToday = UsageTotals()
    @Published var lastError: String?

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("QRX", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let store: TokenStore
        do { store = try SQLiteStore(path: directory.appendingPathComponent("tokenx.sqlite").path) } catch { store = InMemoryStore() }
        server = TokenServer(store: store, cipher: KeychainCipher(service: "net.bcl.qrx.tokenx"))
        provider = TokenXMindProvider(client: TokenClient(broker: server))
        settings = server.settings
        refresh()
    }

    var isReady: Bool { server.isReady }

    /// What answers a character right now, for the settings panel.
    var characterModel: String? { server.model(for: .character).map { "\($0.name) (\($0.provider.displayName))" } }

    func refresh() {
        settings = server.settings
        configured = server.configuredProviders
        usageToday = server.usageToday()
    }

    func activate(_ provider: ProviderKind, key: String?) {
        do {
            try server.activate(provider, key: key?.isEmpty == false ? key : nil)
            lastError = nil
        } catch { lastError = "\(error)" }
        refresh()
    }

    func removeKey(for provider: ProviderKind) {
        do { try server.setKey(nil, for: provider) } catch { lastError = "\(error)" }
        refresh()
    }

    func update(_ change: (inout Settings) -> Void) {
        do { try server.update(change) } catch { lastError = "\(error)" }
        refresh()
    }
}
