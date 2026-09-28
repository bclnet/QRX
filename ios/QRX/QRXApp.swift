//
//  QRXApp.swift
//  QRX
//
//  SwiftUI app lifecycle (the reference app used a storyboard and an
//  AppDelegate). One AppModel owns lookup, Bluetooth and chrome state.
//

import SwiftUI

@main
struct QRXApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .onAppear { model.start() }
        }
    }
}
