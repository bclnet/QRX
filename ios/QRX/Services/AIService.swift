//
//  AIService.swift
//  QRX
//
//  The app's TokenX: the standard server (keys encrypted in SQLite, cipher
//  key in the Keychain) behind TokenXModel, plus the JsonMind provider handed
//  to the scenes so actors' minds can think. Settings UI comes from TokenXUI.
//

import Foundation
import TokenX
import TokenXApple
import JsonMindTokenX

@MainActor
final class AIService {
    /// Settings, usage and commands; the settings screen embeds TokenXSettingsSection(model:) on it.
    let model: TokenXModel
    /// The provider scenes attach to their actors' minds.
    let provider: TokenXMindProvider

    init(appId: String = "net.bcl.qrx") {
        model = TokenXModel(appId: appId)
        provider = TokenXMindProvider(client: model.client)
    }

    var isReady: Bool { model.isReady }
}
