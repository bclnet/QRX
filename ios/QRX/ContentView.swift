//
//  ContentView.swift
//  QRX
//
//  The AR scene with the chrome overlaid, toasts, the keyboard bar and the
//  settings sheet.
//

import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack(alignment: .top) {
            ARGlyphView()
                .ignoresSafeArea()
            ChromeView()
            KeyboardBar()
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

/// A bar above the keyboard with Done, for typing into a form glyph. The fields live on a plane in the
/// scene, where nothing else can give the keyboard up, and a number pad has no return key.
struct KeyboardBar: View {
    @State private var keyboardIsUp = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            if keyboardIsUp {
                HStack {
                    Spacer()
                    Button("Done") {
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    }
                    .font(.body.bold())
                    .padding(.horizontal, 16).padding(.vertical, 10)
                }
                .background(.bar)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardIsUp = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardIsUp = false }
    }
}
