//
//  BluetoothService.swift
//  QRX
//
//  Facade over the three Bluetooth roles: the BLUE/1.0 client (BlueCentral),
//  the BLUE/1.0 server (BluePeripheral) and the Particle LED board client
//  (ParticleLedClient).
//

import Foundation
import Combine
import QRXCore

@MainActor
final class BluetoothService: ObservableObject {
    let glyphService = BlueGlyphService()
    let central: BlueCentral
    let peripheral: BluePeripheral
    let led = ParticleLedClient()

    @Published var serverEnabled: Bool {
        didSet { UserDefaults.standard.set(serverEnabled, forKey: "qrx.blue.server") }
    }
    @Published var localName: String {
        didSet {
            UserDefaults.standard.set(localName, forKey: "qrx.blue.name")
            peripheral.localName = localName
        }
    }

    private var cancellables: Set<AnyCancellable> = []

    init() {
        let name = UserDefaults.standard.string(forKey: "qrx.blue.name") ?? BluetoothService.defaultName
        serverEnabled = UserDefaults.standard.object(forKey: "qrx.blue.server") as? Bool ?? false
        localName = name
        central = BlueCentral()
        peripheral = BluePeripheral(router: glyphService.router, localName: name)
        glyphService.ledColor = { [led] in led.isConnected ? led.color : nil }
        glyphService.setLedColor = { [led] color in led.write(color) }
        glyphService.batteryLevel = { [led] in led.batteryLevel }
        // Re-publish child changes so SwiftUI views observing the service update.
        for publisher in [central.objectWillChange.eraseToAnyPublisher(), peripheral.objectWillChange.eraseToAnyPublisher(), led.objectWillChange.eraseToAnyPublisher()] {
            publisher.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        }
    }

    static var defaultName: String {
        #if os(iOS)
        return UIDevice.current.name.isEmpty ? BlueUUIDs.defaultLocalName : UIDevice.current.name
        #else
        return BlueUUIDs.defaultLocalName
        #endif
    }

    func startLedClient() { led.start() }

    func startServerIfEnabled() { if serverEnabled { peripheral.start() } }

    func setServerEnabled(_ enabled: Bool) {
        serverEnabled = enabled
        enabled ? peripheral.start() : peripheral.stop()
    }

    var serverState: String { peripheral.state }

    var isAnythingConnected: Bool { led.isConnected || peripheral.subscriberCount > 0 || central.isConnected }
}

#if os(iOS)
import UIKit
#endif
