//
//  SpeechInput.swift
//  QRX
//
//  Push-to-talk speech recognition: the microphone button starts a
//  transcription, stopping it (or a pause in speech) hands the text to the
//  scenes as a `spoken` event.
//

import Foundation
import Combine
import AVFoundation
import Speech

@MainActor
final class SpeechInput: ObservableObject {
    @Published private(set) var isListening = false
    @Published private(set) var transcript = ""
    @Published var error: String?
    /// Called with the final text when listening stops.
    var onHeard: ((String) -> Void)?

    private let recognizer = SFSpeechRecognizer()
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    var isAvailable: Bool { recognizer?.isAvailable ?? false }

    func toggle() { if isListening { stop() } else { start() } }

    func start() {
        guard !isListening else { return }
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            Task { @MainActor in
                guard status == .authorized else { self?.error = "Speech recognition is not allowed."; return }
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    Task { @MainActor in
                        guard granted else { self?.error = "Microphone access is not allowed."; return }
                        self?.beginRecognition()
                    }
                }
            }
        }
    }

    private func beginRecognition() {
        guard let recognizer = recognizer, recognizer.isAvailable else { error = "Speech recognition is unavailable."; return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            self.request = request
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
            engine.prepare()
            try engine.start()
            transcript = ""
            error = nil
            isListening = true
            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                Task { @MainActor in
                    guard let self = self else { return }
                    if let result = result { self.transcript = result.bestTranscription.formattedString; if result.isFinal { self.stop() } }
                    if error != nil, self.isListening { self.stop() }
                }
            }
        } catch {
            self.error = "Could not start listening: \(error.localizedDescription)"
            stop()
        }
    }

    func stop() {
        guard isListening || engine.isRunning else { return }
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        isListening = false
        if !text.isEmpty { onHeard?(text) }
    }
}
