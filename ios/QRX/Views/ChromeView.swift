//
//  ChromeView.swift
//  QRX
//
//  The overlay over the camera: settings/Bluetooth, the status message,
//  forget-glyphs and the torch.
//

import SwiftUI

struct ChromeView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        HStack(alignment: .top) {
            VStack {
                Button { model.showSettings = true } label: {
                    Image(systemName: model.bluetooth.isAnythingConnected ? "person.crop.circle.badge.checkmark" : "person.crop.circle")
                        .font(.largeTitle)
                }
                .padding()
                .accessibilityLabel(Text("Settings and Bluetooth"))
                Spacer()
            }
            Spacer()
            VStack {
                Text(model.title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
                    .padding(.top, 12)
                if !model.foundGlyphs.isEmpty {
                    Text("\(model.foundGlyphs.count) glyph\(model.foundGlyphs.count == 1 ? "" : "s")")
                        .font(.caption).foregroundColor(.secondary)
                }
                Spacer()
            }
            Spacer()
            VStack {
                Button { model.forgetGlyphs() } label: {
                    Image(systemName: "arrow.counterclockwise.circle").font(.largeTitle)
                }
                .padding(.top).padding(.horizontal)
                .accessibilityLabel(Text("Forget glyphs"))
                Button { model.toggleFlash() } label: {
                    Image(systemName: model.flashOn ? "bolt.fill" : "bolt").font(.largeTitle)
                }
                .padding()
                .accessibilityLabel(Text("Torch"))
                Spacer()
            }
        }
    }
}
