//
//  SettingsView.swift
//  QRX
//
//  Bluetooth panel: the BLUE server, nearby QRX devices, and the Particle
//  LED board (sliders and battery).
//

import SwiftUI
import QRXCore

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var red: Double = 0
    @State private var green: Double = 0
    @State private var blue: Double = 0

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Share glyphs over Bluetooth"), footer: Text("Other QRX devices can show a code with blue://\(model.bluetooth.localName)/glyph/<name>.")) {
                    Toggle("BLUE/1.0 server", isOn: Binding(get: { model.bluetooth.serverEnabled }, set: { model.bluetooth.setServerEnabled($0) }))
                    TextField("Device name", text: Binding(get: { model.bluetooth.localName }, set: { model.bluetooth.localName = $0 }))
                    LabeledContent("State", value: model.bluetooth.serverState)
                    ForEach(model.bluetooth.glyphService.names, id: \.self) { name in
                        Label(name, systemImage: "qrcode")
                    }
                }
                Section(header: Text("Nearby QRX devices")) {
                    if model.bluetooth.central.discovered.isEmpty {
                        Text(model.bluetooth.central.isScanning ? "Scanning…" : "None found").foregroundColor(.secondary)
                    }
                    ForEach(model.bluetooth.central.discovered) { device in
                        HStack {
                            Text(device.name)
                            Spacer()
                            Text("\(device.rssi) dBm").font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Button(model.bluetooth.central.isScanning ? "Stop scanning" : "Scan") {
                        model.bluetooth.central.isScanning ? model.bluetooth.central.stopScan() : model.bluetooth.central.startScan()
                    }
                }
                Section(header: Text("Particle LED board"), footer: Text("Service \(ParticleUUIDs.ledService)")) {
                    LabeledContent("State", value: model.bluetooth.led.state)
                    if let battery = model.bluetooth.led.batteryLevel { LabeledContent("Battery", value: "\(battery)%") }
                    slider("Red", $red, .red)
                    slider("Green", $green, .green)
                    slider("Blue", $blue, .blue)
                    Button("Off") { red = 0; green = 0; blue = 0; write() }
                }
                Section(header: Text("Found glyphs")) {
                    if model.foundGlyphs.isEmpty { Text("None yet").foregroundColor(.secondary) }
                    ForEach(model.foundGlyphs) { glyph in
                        VStack(alignment: .leading) {
                            Text(glyph.barcode.location ?? glyph.barcode.payload).font(.caption).lineLimit(2)
                            Text(glyph.status).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("QRX")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onAppear {
            let color = model.bluetooth.led.color
            red = Double(color.red); green = Double(color.green); blue = Double(color.blue)
        }
    }

    private func slider(_ title: String, _ value: Binding<Double>, _ tint: Color) -> some View {
        HStack {
            Text(title).frame(width: 60, alignment: .leading)
            Slider(value: value, in: 0...255, step: 1) { editing in if !editing { write() } }.tint(tint)
            Text("\(Int(value.wrappedValue))").font(.caption.monospacedDigit()).frame(width: 36)
        }
        .disabled(!model.bluetooth.led.isConnected)
    }

    private func write() {
        _ = model.bluetooth.led.write(LedColor(r: red, g: green, b: blue))
    }
}
