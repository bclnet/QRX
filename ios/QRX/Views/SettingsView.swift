//
//  SettingsView.swift
//  QRX
//
//  Bluetooth panel: the BLUE server, nearby QRX devices, and the Particle
//  LED board (sliders and battery).
//

import SwiftUI
import QRXCore
import TokenX

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var red: Double = 0
    @State private var green: Double = 0
    @State private var blue: Double = 0
    @State private var aiProvider: ProviderKind = .anthropic
    @State private var aiKey = ""
    @State private var localURL = ""
    @State private var localModel = ""

    var body: some View {
        NavigationView {
            Form {
                aiSection
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
            model.ai.refresh()
            aiProvider = model.ai.settings.activeProvider ?? .anthropic
            localURL = model.ai.settings.localBaseURL ?? ""
            localModel = model.ai.settings.localModel ?? ""
        }
    }

    /// TokenX: the provider the actors' minds use, its key, and today's usage. Keys are stored encrypted.
    private var aiSection: some View {
        Section(header: Text("AI (TokenX)"), footer: Text(model.ai.characterModel.map { "Characters answer with \($0)." } ?? "Pick a provider and enter its API key; until then actors use their canned rules.")) {
            Picker("Provider", selection: $aiProvider) {
                ForEach(ProviderKind.allCases, id: \.self) { kind in
                    Text(model.ai.configured.contains(kind) ? "\(kind.displayName) ✓" : kind.displayName).tag(kind)
                }
            }
            if aiProvider.needsKey {
                SecureField(model.ai.configured.contains(aiProvider) ? "API key (stored; enter to replace)" : "API key", text: $aiKey)
                    .textInputAutocapitalization(.never).disableAutocorrection(true)
            } else {
                TextField("Server URL, e.g. http://192.168.1.20:11434/v1", text: $localURL).textInputAutocapitalization(.never).disableAutocorrection(true).keyboardType(.URL)
                TextField("Model name, e.g. llama3", text: $localModel).textInputAutocapitalization(.never).disableAutocorrection(true)
            }
            Button(model.ai.settings.activeProvider == aiProvider && aiKey.isEmpty ? "Active" : "Use \(aiProvider.displayName)") {
                if !aiProvider.needsKey { model.ai.update { $0.localBaseURL = localURL.isEmpty ? nil : localURL; $0.localModel = localModel.isEmpty ? nil : localModel } }
                model.ai.activate(aiProvider, key: aiKey)
                aiKey = ""
            }
            .disabled(aiProvider.needsKey && aiKey.isEmpty && !model.ai.configured.contains(aiProvider))
            if model.ai.configured.contains(aiProvider) {
                Button("Remove key", role: .destructive) { model.ai.removeKey(for: aiProvider) }
            }
            Toggle("Keep prompts in the usage log", isOn: Binding(get: { model.ai.settings.logPrompts }, set: { on in model.ai.update { $0.logPrompts = on } }))
            LabeledContent("Today", value: String(format: "%d requests, %d tokens, $%.4f", model.ai.usageToday.requests, model.ai.usageToday.totalTokens, model.ai.usageToday.costUSD))
            if let error = model.ai.lastError { Text(error).font(.caption).foregroundColor(.red) }
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
