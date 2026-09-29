//
//  SettingsView.swift
//  QRX
//
//  Settings: AI (TokenX), the BLUE server, nearby QRX devices and the found glyphs.
//

import SwiftUI
import QRXCore
import TokenXUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                TokenXSettingsSection(model: model.ai.model, title: "AI (TokenX)")
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
    }
}
