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
    /// Counts "Forget"; the AR view drops its tracked codes when this changes.
    @Published private(set) var forgetCount = 0

    // MARK: Services
    let bluetooth = BluetoothService()
    lazy var lookup = GlyphLookup(fetcher: URLSessionFetcher(), blue: bluetooth.central)
    /// TokenX: providers, keys and usage, plus the JsonMind provider for scene actors.
    let ai = AIService()
    /// Push-to-talk speech, delivered to scenes as `spoken` events.
    let speech = SpeechInput()
    /// Host actions offered to `_ui` glyphs and `_button` actions.
    let actions = JsonActions()
    /// Scenes register here to receive what the user said.
    var heardHandlers: [(String) -> Void] = []
    /// Called when push-to-talk stops and playback audio is available again.
    var listeningEndedHandlers: [() -> Void] = []

    private var toastTask: Task<Void, Never>?
    private var cancellables: Set<AnyCancellable> = []
    /// The title to return to when an interrupted AR session resumes.
    private var titleBeforeInterruption: String?
    /// Callbacks of sightings that came while the code's lookup was still running.
    private var waiting: [String: [(GlyphDocument?, String?) -> Void]] = [:]

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
        speech.onHeard = { [weak self] text in self?.heard(text) }
        speech.onStopped = { [weak self] in self?.listeningEndedHandlers.forEach { $0() } }
        // The chrome observes this model but shows speech and Bluetooth state, so re-publish their changes.
        for publisher in [speech.objectWillChange.eraseToAnyPublisher(), bluetooth.objectWillChange.eraseToAnyPublisher()] {
            publisher.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        }
    }

    /// Delivers recognised speech to every scene on the stage.
    func heard(_ text: String) {
        showToast("“\(text)”")
        heardHandlers.forEach { $0(text) }
    }

    func start() {
        bluetooth.startServerIfEnabled()
    }

    // MARK: - Glyphs

    /// Called by the barcode detector when a code is first seen; resolves its document, or says why there is none.
    func resolve(_ barcode: GlyphBarcode, completion: @escaping (GlyphDocument?, _ error: String?) -> Void) {
        if let index = foundGlyphs.firstIndex(where: { $0.id == barcode.id }) {
            let glyph = foundGlyphs[index]
            if glyph.document == nil && glyph.error == nil { waiting[barcode.id, default: []].append(completion) }
            else { completion(glyph.document, glyph.error) }
            return
        }
        foundGlyphs.append(FoundGlyph(id: barcode.id, barcode: barcode))
        title = barcode.isBluetooth ? "Fetching over Bluetooth…" : "Loading glyph…"
        lookup.lookup(barcode) { [weak self] result in
            Task { @MainActor in
                guard let self = self, let index = self.foundGlyphs.firstIndex(where: { $0.id == barcode.id }) else { completion(nil, nil); return }
                let callbacks = [completion] + (self.waiting.removeValue(forKey: barcode.id) ?? [])
                switch result {
                case .success(let document):
                    self.foundGlyphs[index].document = document
                    self.title = "Showing \(document.content.typeName) glyph"
                    callbacks.forEach { $0(document, nil) }
                case .failure(let error):
                    self.foundGlyphs[index].error = "\(error)"
                    self.title = "Glyph failed: \(error)"
                    callbacks.forEach { $0(nil, "\(error)") }
                }
            }
        }
    }

    func forgetGlyphs() {
        foundGlyphs.removeAll()
        waiting.removeAll()
        forgetCount += 1
        lookup.clearCache()
        title = "Look for a QR code."
    }

    // MARK: - AR session

    static let interruptedTitle = "Camera paused."

    /// The AR session stopped; `message` stays in the chrome until tracking is restarted ("Forget" does that).
    func sessionFailed(_ message: String) {
        titleBeforeInterruption = nil
        title = message
    }

    func sessionInterrupted() {
        if titleBeforeInterruption == nil { titleBeforeInterruption = title }
        title = AppModel.interruptedTitle
    }

    func sessionInterruptionEnded() {
        // Only put the old title back if nothing else has replaced the paused one meanwhile.
        if let previous = titleBeforeInterruption, title == AppModel.interruptedTitle { title = previous }
        titleBeforeInterruption = nil
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
