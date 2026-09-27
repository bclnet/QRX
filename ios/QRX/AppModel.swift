//
//  AppModel.swift
//  QRX
//
//  Application state: glyph lookup, the Bluetooth service, chrome messages,
//  and the JsonUI host actions offered to `_ui` glyphs.
//

import Foundation
import SwiftUI
import Combine
import QRXCore
import JsonUI

@MainActor
final class AppModel: ObservableObject {
    // MARK: Chrome
    @Published var title = "Look for a QR code."
    @Published var flashOn = false
    @Published var toast: String?
    @Published var showSettings = false
    @Published var foundGlyphs: [FoundGlyph] = []

    // MARK: Services
    let bluetooth = BluetoothService()
    lazy var lookup = GlyphLookup(fetcher: URLSessionFetcher(), blue: bluetooth.central)
    /// Host actions offered to `_ui` glyphs and `_button` actions.
    let actions = JsonActions()

    private var toastTask: Task<Void, Never>?

    struct FoundGlyph: Identifiable, Equatable {
        let id: String
        let barcode: GlyphBarcode
        var document: GlyphDocument?
        var error: String?
        var status: String { error ?? document?.content.typeName ?? "loading" }
    }

    init() {
        registerActions()
        shareBundledExamples()
    }

    func start() {
        bluetooth.startLedClient()
        bluetooth.startServerIfEnabled()
    }

    // MARK: - Glyphs

    /// Called by the barcode detector when a code is first seen; resolves its document.
    func resolve(_ barcode: GlyphBarcode, completion: @escaping (GlyphDocument?) -> Void) {
        if let index = foundGlyphs.firstIndex(where: { $0.id == barcode.id }) {
            completion(foundGlyphs[index].document)
            return
        }
        foundGlyphs.append(FoundGlyph(id: barcode.id, barcode: barcode))
        title = barcode.isBluetooth ? "Fetching over Bluetooth…" : "Loading glyph…"
        lookup.lookup(barcode) { [weak self] result in
            Task { @MainActor in
                guard let self = self, let index = self.foundGlyphs.firstIndex(where: { $0.id == barcode.id }) else { completion(nil); return }
                switch result {
                case .success(let document):
                    self.foundGlyphs[index].document = document
                    self.title = "Showing \(document.content.typeName) glyph"
                    completion(document)
                case .failure(let error):
                    self.foundGlyphs[index].error = "\(error)"
                    self.title = "Glyph failed: \(error)"
                    completion(nil)
                }
            }
        }
    }

    func forgetGlyphs() {
        foundGlyphs.removeAll()
        lookup.clearCache()
        title = "Look for a QR code."
    }

    // MARK: - Actions

    func showToast(_ message: String) {
        toast = message
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            if !Task.isCancelled { self?.toast = nil }
        }
    }

    func toggleFlash() {
        flashOn.toggle()
        AppServices.setTorch(on: flashOn)
    }

    /// Performs a `_button` action.
    func perform(_ action: JsonAction?) {
        guard let action = action else { showToast("Tapped"); return }
        let runtime = JsonRuntime(actions: actions)
        runtime.context.perform(action)
    }

    private func registerActions() {
        actions.register("toast") { [weak self] _, args, _ in
            Task { @MainActor in self?.showToast(args["message"].stringValue ?? args.stringValue ?? "Done") }
            return nil
        }
        actions.register("open") { _, args, _ in
            if let text = args["url"].stringValue ?? args.stringValue, let url = URL(string: text) {
                Task { @MainActor in _ = await UIApplication.shared.open(url) }
            }
            return nil
        }
        actions.register("led") { [weak self] _, args, _ in
            guard let color = LedColor(json: args) else { return ["error": "expected r, g, b"] }
            let written = self?.bluetooth.led.write(color) ?? false
            return ["written": .bool(written)]
        }
        actions.register("dismiss") { [weak self] _, _, _ in
            Task { @MainActor in self?.forgetGlyphs() }
            return nil
        }
        actions.fallback = { [weak self] name, args, _ in
            Task { @MainActor in self?.showToast("\(name) \(args.jsonString())") }
            return nil
        }
    }

    /// Bundled example documents are shared over Bluetooth so another QRX can fetch them with `blue://` codes.
    private func shareBundledExamples() {
        guard let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "examples") else { return }
        for url in urls {
            if let data = try? Data(contentsOf: url), let document = try? GlyphDocument(data: data) {
                bluetooth.glyphService.share(document, as: url.deletingPathExtension().lastPathComponent)
            }
        }
    }
}
