//
//  BlueCentral.swift
//  QRX
//
//  BLUE/1.0 client over CoreBluetooth: scans for QRX peripherals, connects
//  to the one named in a `blue://` code (or any), writes the request in
//  chunks to the request characteristic and assembles the response from
//  notifications. Implements QRXCore's BlueTransport for GlyphLookup.
//

import Foundation
import CoreBluetooth
import Combine
import QRXCore

@MainActor
final class BlueCentral: NSObject, ObservableObject, BlueTransport {
    struct Device: Identifiable, Equatable {
        let id: UUID
        let name: String
        var rssi: Int
    }

    enum BlueCentralError: Error, CustomStringConvertible {
        case poweredOff, deviceNotFound(String), disconnected, timeout, busy, characteristicsMissing
        var description: String {
            switch self {
            case .poweredOff: return "Bluetooth is off"
            case .deviceNotFound(let name): return "no QRX device named \(name) nearby"
            case .disconnected: return "disconnected"
            case .timeout: return "the device did not answer"
            case .busy: return "a request is already in progress"
            case .characteristicsMissing: return "the device does not expose the QRX service"
            }
        }
    }

    @Published private(set) var discovered: [Device] = []
    @Published private(set) var isScanning = false
    @Published private(set) var isConnected = false
    @Published private(set) var state = "idle"

    private lazy var manager = CBCentralManager(delegate: self, queue: nil)
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var connected: CBPeripheral?
    private var requestCharacteristic: CBCharacteristic?
    private var responseCharacteristic: CBCharacteristic?
    private let assembler = BlueAssembler()

    private struct Pending {
        let request: BlueRequest
        let device: String
        let completion: (Result<BlueResponse, Error>) -> Void
        var timeout: DispatchWorkItem?
    }
    private var pending: Pending?
    private var connectTarget: String?
    /// True while a scan was started to find a request's device, as opposed to from the settings screen.
    private var scanningForRequest = false
    var timeout: TimeInterval = 15

    private let serviceUUID = CBUUID(string: BlueUUIDs.service)
    private let requestUUID = CBUUID(string: BlueUUIDs.request)
    private let responseUUID = CBUUID(string: BlueUUIDs.response)

    func startScan() {
        guard manager.state == .poweredOn else { state = "Bluetooth is \(manager.state.description)"; return }
        discovered.removeAll()
        isScanning = true
        state = "scanning"
        manager.scanForPeripherals(withServices: [serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScan() {
        manager.stopScan()
        isScanning = false
        scanningForRequest = false
        if state == "scanning" { state = "idle" }
    }

    // MARK: - BlueTransport

    nonisolated func send(_ request: BlueRequest, to device: String, completion: @escaping (Result<BlueResponse, Error>) -> Void) {
        Task { @MainActor in self.perform(request, to: device, completion: completion) }
    }

    private func perform(_ request: BlueRequest, to device: String, completion: @escaping (Result<BlueResponse, Error>) -> Void) {
        guard pending == nil else { completion(.failure(BlueCentralError.busy)); return }
        guard manager.state == .poweredOn else { completion(.failure(BlueCentralError.poweredOff)); return }
        let work = DispatchWorkItem { [weak self] in self?.fail(BlueCentralError.timeout) }
        pending = Pending(request: request, device: device, completion: completion, timeout: work)
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: work)
        if let connected = connected, connected.state == .connected, requestCharacteristic != nil, matches(connected, device) {
            write(request)
        } else {
            connectTarget = device
            state = "looking for \(device)"
            if let known = peripherals.values.first(where: { matches($0, device) }) {
                connect(known)
            } else {
                scanForRequest()
            }
        }
    }

    private func scanForRequest() {
        guard !isScanning else { return }
        startScan()
        scanningForRequest = isScanning
    }

    private func matches(_ peripheral: CBPeripheral, _ device: String) -> Bool {
        device == "*" || peripheral.name?.caseInsensitiveCompare(device) == .orderedSame
            || discovered.first { $0.id == peripheral.identifier }?.name.caseInsensitiveCompare(device) == .orderedSame
    }

    private func connect(_ peripheral: CBPeripheral) {
        stopScan()
        connected = peripheral
        peripheral.delegate = self
        state = "connecting to \(peripheral.name ?? "device")"
        manager.connect(peripheral, options: nil)
    }

    private func write(_ request: BlueRequest) {
        guard let peripheral = connected, let characteristic = requestCharacteristic else { fail(BlueCentralError.characteristicsMissing); return }
        assembler.reset()
        let mtu = peripheral.maximumWriteValueLength(for: .withoutResponse) + 3
        let type: CBCharacteristicWriteType = characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        state = "sending \(request.method) \(request.uri)"
        for frame in BlueFramer.frames(for: request.text, mtu: mtu) {
            peripheral.writeValue(frame, for: characteristic, type: type)
        }
    }

    private func finish(_ result: Result<BlueResponse, Error>) {
        guard let pending = pending else { return }
        pending.timeout?.cancel()
        self.pending = nil
        // The request is over, answered or not: stop looking for its device and give up a connection
        // that never completed, so a code for an absent device does not leave the radio busy.
        connectTarget = nil
        if scanningForRequest { scanningForRequest = false; stopScan() }
        if let peripheral = connected, peripheral.state == .connecting {
            manager.cancelPeripheralConnection(peripheral)
            connected = nil
        }
        state = "idle"
        pending.completion(result)
    }

    private func fail(_ error: Error) { finish(.failure(error)) }
}

extension BlueCentral: CBCentralManagerDelegate, CBPeripheralDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            self.state = "Bluetooth is \(central.state.description)"
            if central.state == .poweredOn, self.pending != nil { self.scanForRequest() }
            if central.state != .poweredOn { self.isConnected = false; self.fail(BlueCentralError.poweredOff) }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "QRX"
        Task { @MainActor in
            self.peripherals[peripheral.identifier] = peripheral
            if let index = self.discovered.firstIndex(where: { $0.id == peripheral.identifier }) {
                self.discovered[index].rssi = RSSI.intValue
            } else {
                self.discovered.append(Device(id: peripheral.identifier, name: name, rssi: RSSI.intValue))
            }
            if let target = self.connectTarget, target == "*" || name.caseInsensitiveCompare(target) == .orderedSame {
                self.connectTarget = nil
                self.connect(peripheral)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.isConnected = true
            self.state = "discovering services"
            peripheral.discoverServices([self.serviceUUID])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in self.fail(error ?? BlueCentralError.disconnected) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.isConnected = false
            self.connected = nil
            self.requestCharacteristic = nil
            self.responseCharacteristic = nil
            self.fail(error ?? BlueCentralError.disconnected)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == CBUUID(string: BlueUUIDs.service) }) else {
            Task { @MainActor in self.fail(error ?? BlueCentralError.characteristicsMissing) }
            return
        }
        peripheral.discoverCharacteristics([CBUUID(string: BlueUUIDs.request), CBUUID(string: BlueUUIDs.response)], for: service)
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let request = service.characteristics?.first { $0.uuid == CBUUID(string: BlueUUIDs.request) }
        let response = service.characteristics?.first { $0.uuid == CBUUID(string: BlueUUIDs.response) }
        Task { @MainActor in
            self.requestCharacteristic = request
            self.responseCharacteristic = response
            guard let response = response, request != nil else { self.fail(BlueCentralError.characteristicsMissing); return }
            peripheral.setNotifyValue(true, for: response)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            if let error = error { self.fail(error); return }
            if characteristic.isNotifying, let pending = self.pending { self.write(pending.request) }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        Task { @MainActor in
            do {
                if let text = try self.assembler.append(data).first {
                    self.finish(Result { try BlueResponse(text: text) })
                }
            } catch {
                self.fail(error)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error { Task { @MainActor in self.fail(error) } }
    }
}

extension CBManagerState {
    var description: String {
        switch self {
        case .poweredOn: return "on"
        case .poweredOff: return "off"
        case .unauthorized: return "not authorized"
        case .unsupported: return "unsupported"
        case .resetting: return "resetting"
        default: return "unknown"
        }
    }
}
