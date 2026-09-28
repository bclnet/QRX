//
//  QRXApp.swift
//  QRX
//
//  SwiftUI app lifecycle. One AppModel owns lookup, Bluetooth and chrome state.
//

import SwiftUI
import JsonScene

@main
struct QRXApp: App {
    @StateObject private var model = AppModel()

    init() {
        // `_ui` glyphs whose root is a Scene render with JsonScene (SceneKit).
        JsonSceneNode.register()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .onAppear { model.start() }
        }
    }
}
