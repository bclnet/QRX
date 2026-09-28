//
//  ParticleLedClient.swift
//  QRX
//
//  Client for the Particle LED example board, a BLE peripheral: scans for
//  the LED service, connects, writes the
//  three colour characteristics and subscribes to the battery level.
//

import Foundation
import CoreBluetooth
import Combine
import QRXCore

@MainActor
final class ParticleLedClient: NSObject, ObservableObject {
    @Published private(set) var state = "idle"
    @Published private(set) var isConnected = false
    @Published private(set) var batteryLevel: Int?
    @Published private(set) var color = LedColor.off

    private lazy var manager = CBCentralManager(delegate: self, queue: nil)
    private var peripheral: CBPeripheral?
    private var characteristics: [LedColor.Channel: CBCharacteristic] = [:]
    private var batteryCharacteristic: CBCharacteristic?
    private var wantsScan = false

    private let ledService = CBUUID(string: ParticleUUIDs.ledService)
    private let batteryService = CBUUID(string: ParticleUUIDs.batteryService)

    /// Starts scanning for a board; reconnects when the board goes away.
    func start() {
        wantsScan = true
        scan()
    }

    func stop() {
        wantsScan = false
        manager.stopScan()
        if let peripheral = peripheral { manager.cancelPeripheralConnection(peripheral) }
        state = "idle"
    }

    private func scan() {
        guard manager.state == .poweredOn else { state = "waiting for Bluetooth (\(manager.state.description))"; return }
        state = "scanning for LED board"
        manager.scanForPeripherals(withServices: [ledService], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    /// Writes the colour; false when no board is connected.
    @discardableResult
    func write(_ color: LedColor) -> Bool {
        self.color = color
        guard let peripheral = peripheral, isConnected else { return false }
        for channel in LedColor.Channel.allCases {
            guard let characteristic = characteristics[channel] else { continue }
            let type: CBCharacteristicWriteType = characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            peripheral.writeValue(color.data(for: channel), for: characteristic, type: type)
        }
        return characteristics.count == LedColor.Channel.allCases.count
    }
}

extension ParticleLedClient: CBCentralManagerDelegate, CBPeripheralDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            if central.state == .poweredOn { if self.wantsScan { self.scan() } }
            else { self.isConnected = false; self.state = "Bluetooth is \(central.state.description)" }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        Task { @MainActor in
            central.stopScan()
            self.peripheral = peripheral
            peripheral.delegate = self
            self.state = "connecting to \(peripheral.name ?? "board")"
            central.connect(peripheral, options: nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.state = "connected to \(peripheral.name ?? "board")"
            peripheral.discoverServices([self.ledService, self.batteryService])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in self.peripheral = nil; if self.wantsScan { self.scan() } }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.isConnected = false
            self.peripheral = nil
            self.characteristics.removeAll()
            self.batteryCharacteristic = nil
            self.batteryLevel = nil
            if self.wantsScan { self.scan() } else { self.state = "disconnected" }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        for service in peripheral.services ?? [] {
            if service.uuid == CBUUID(string: ParticleUUIDs.ledService) {
                peripheral.discoverCharacteristics(LedColor.Channel.allCases.map { CBUUID(string: $0.characteristicUUID) }, for: service)
            } else if service.uuid == CBUUID(string: ParticleUUIDs.batteryService) {
                peripheral.discoverCharacteristics([CBUUID(string: ParticleUUIDs.batteryLevel)], for: service)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let found = service.characteristics ?? []
        Task { @MainActor in
            for characteristic in found {
                if let channel = LedColor.Channel.allCases.first(where: { CBUUID(string: $0.characteristicUUID) == characteristic.uuid }) {
                    self.characteristics[channel] = characteristic
                } else if characteristic.uuid == CBUUID(string: ParticleUUIDs.batteryLevel) {
                    self.batteryCharacteristic = characteristic
                    peripheral.setNotifyValue(true, for: characteristic)
                    peripheral.readValue(for: characteristic)
                }
            }
            if self.characteristics.count == LedColor.Channel.allCases.count {
                self.isConnected = true
                self.state = "ready (\(peripheral.name ?? "board"))"
                self.write(self.color)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == CBUUID(string: ParticleUUIDs.batteryLevel), let byte = characteristic.value?.first else { return }
        Task { @MainActor in self.batteryLevel = Int(byte) }
    }
}
