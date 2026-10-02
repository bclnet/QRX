//
//  BluePeripheral.swift
//  QRX
//
//  BLUE/1.0 server over CoreBluetooth: advertises the QRX service,
//  reassembles request chunks written by centrals, routes them through the
//  BlueRouter and notifies the response chunks, honouring
//  peripheralManagerIsReady(toUpdateSubscribers:) for flow control.
//

import Foundation
import CoreBluetooth
import Combine
import QRXCore

@MainActor
final class BluePeripheral: NSObject, ObservableObject {
    @Published private(set) var state = "stopped"
    @Published private(set) var subscriberCount = 0
    @Published private(set) var requestCount = 0

    var localName: String {
        didSet { if isAdvertising { restartAdvertising() } }
    }

    private let router: BlueRouter
    private lazy var manager = CBPeripheralManager(delegate: self, queue: nil)
    private var service: CBMutableService?
    private var responseCharacteristic: CBMutableCharacteristic?
    private var assemblers: [UUID: BlueAssembler] = [:]
    /// Response chunks by the central that asked; a response is only ever sent to that central.
    private var outbox = BlueOutbox<UUID>()
    /// The centrals subscribed to the response characteristic.
    private var subscribers: [UUID: CBCentral] = [:]
    private var isAdvertising = false
    private var wantsStart = false

    init(router: BlueRouter, localName: String) {
        self.router = router
        self.localName = localName
    }

    func start() {
        wantsStart = true
        guard manager.state == .poweredOn else { state = "waiting for Bluetooth (\(manager.state.description))"; return }
        if service == nil { addService() }
        restartAdvertising()
    }

    func stop() {
        wantsStart = false
        manager.stopAdvertising()
        isAdvertising = false
        state = "stopped"
    }

    private func addService() {
        let request = CBMutableCharacteristic(type: CBUUID(string: BlueUUIDs.request), properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
        let response = CBMutableCharacteristic(type: CBUUID(string: BlueUUIDs.response), properties: [.notify, .read], value: nil, permissions: [.readable])
        let service = CBMutableService(type: CBUUID(string: BlueUUIDs.service), primary: true)
        service.characteristics = [request, response]
        manager.add(service)
        self.service = service
        self.responseCharacteristic = response
    }

    private func restartAdvertising() {
        manager.stopAdvertising()
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: BlueUUIDs.service)],
            CBAdvertisementDataLocalNameKey: localName,
        ])
        isAdvertising = true
        state = "advertising as \(localName)"
    }

    private func handle(_ text: String, from central: CBCentral) {
        requestCount += 1
        let response = router.handle(text: text)
        let mtu = central.maximumUpdateValueLength + 3
        outbox.enqueue(BlueFramer.frames(for: response.text, mtu: mtu), for: central.identifier)
        flush()
    }

    private func flush() {
        guard let characteristic = responseCharacteristic else { return }
        let subscribers = self.subscribers, manager = self.manager
        outbox.flush(to: Set(subscribers.keys)) { id, frame in
            guard let central = subscribers[id] else { return true }
            return manager.updateValue(frame, for: characteristic, onSubscribedCentrals: [central]) // false: retried from peripheralManagerIsReady
        }
    }
}

extension BluePeripheral: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            if peripheral.state == .poweredOn, self.wantsStart { self.start() }
            else if peripheral.state != .poweredOn { self.isAdvertising = false; self.state = "Bluetooth is \(peripheral.state.description)" }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error { Task { @MainActor in self.state = "service failed: \(error.localizedDescription)" } }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error { Task { @MainActor in self.state = "advertising failed: \(error.localizedDescription)"; self.isAdvertising = false } }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        Task { @MainActor in
            self.subscribers[central.identifier] = central
            self.subscriberCount = self.subscribers.count
            self.assemblers[central.identifier] = BlueAssembler()
            self.flush()
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        Task { @MainActor in
            self.subscribers.removeValue(forKey: central.identifier)
            self.subscriberCount = self.subscribers.count
            self.assemblers.removeValue(forKey: central.identifier)
            self.outbox.remove(central.identifier)
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        Task { @MainActor in
            for request in requests {
                guard request.characteristic.uuid == CBUUID(string: BlueUUIDs.request), let data = request.value else {
                    peripheral.respond(to: request, withResult: .attributeNotFound)
                    continue
                }
                let assembler = self.assemblers[request.central.identifier] ?? BlueAssembler()
                self.assemblers[request.central.identifier] = assembler
                do {
                    for text in try assembler.append(data) { self.handle(text, from: request.central) }
                    peripheral.respond(to: request, withResult: .success)
                } catch {
                    peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
                }
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        // Reads return the reader's next pending chunk, for centrals that cannot subscribe.
        Task { @MainActor in
            request.value = self.outbox.next(for: request.central.identifier) ?? Data()
            peripheral.respond(to: request, withResult: .success)
        }
    }

    nonisolated func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        Task { @MainActor in self.flush() }
    }
}
