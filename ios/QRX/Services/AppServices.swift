//
//  AppServices.swift
//  QRX
//
//  Device services used by the chrome (torch), ported from Glyph.
//

import AVFoundation

enum AppServices {
    static func setTorch(on: Bool) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            if on { try device.setTorchModeOn(level: 1.0) } else { device.torchMode = .off }
            device.unlockForConfiguration()
        } catch {
            print("QRX: torch failed: \(error)")
        }
    }
}
