//
//  ContentView.swift
//  QRX
//
//  The AR scene with the chrome overlaid, toasts, and the settings sheet.
//

import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack(alignment: .top) {
            ARGlyphView()
                .ignoresSafeArea()
            ChromeView()
            if let toast = model.toast {
                VStack {
                    Spacer()
                    Text(toast)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 32)
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut, value: model.toast)
        .sheet(isPresented: $model.showSettings) {
            SettingsView().environmentObject(model)
        }
    }
}
