import XCTest
import AVFoundation
@testable import QRX

@MainActor
final class SpeechInputTests: XCTestCase {
    // Listening records, which silences playback; stopping has to put the session back or glyph video stays mute.
    func testListeningPutsTheAudioSessionBack() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .moviePlayback, options: [])
        let speech = SpeechInput()
        // Activating can fail where there is no microphone; the category is switched before that.
        try? speech.beginListeningAudio()
        XCTAssertEqual(session.category, .record)

        speech.endListeningAudio()
        XCTAssertEqual(session.category, .playback)
        XCTAssertEqual(session.mode, .moviePlayback)

        // Ending again, with nothing to put back, leaves a category set since then alone.
        try session.setCategory(.ambient)
        speech.endListeningAudio()
        XCTAssertEqual(session.category, .ambient)
    }

    func testTheModelPassesOnTheEndOfListening() {
        let model = AppModel()
        var ended = 0
        model.listeningEndedHandlers.append { ended += 1 }
        model.speech.onStopped?()
        XCTAssertEqual(ended, 1)
    }
}
