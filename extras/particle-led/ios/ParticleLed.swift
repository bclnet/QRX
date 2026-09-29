//
//  ParticleLed.swift
//  QRX extras (not compiled)
//
//  GATT identifiers of the Particle LED example board and the LED colour
//  value. Lived in QRXCore/Blue/BlueUUIDs.swift while the board was the
//  example that drove the Bluetooth layer.
//

import Foundation
import JsonUICore

/// Particle LED example board.
public enum ParticleUUIDs {
    public static let ledService = "b4250400-fb4b-4746-b2b0-93f0e61122c6"
    public static let redLED = "b4250401-fb4b-4746-b2b0-93f0e61122c6"
    public static let greenLED = "b4250402-fb4b-4746-b2b0-93f0e61122c6"
    public static let blueLED = "b4250403-fb4b-4746-b2b0-93f0e61122c6"
    public static let batteryService = "180f"
    public static let batteryLevel = "2a19"
}

public struct LedColor: Equatable, Hashable {
    public var red: UInt8
    public var green: UInt8
    public var blue: UInt8

    public static let off = LedColor(red: 0, green: 0, blue: 0)

    public init(red: UInt8, green: UInt8, blue: UInt8) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    /// Clamps arbitrary numbers into 0–255.
    public init(r: Double, g: Double, b: Double) {
        self.init(red: LedColor.clamp(r), green: LedColor.clamp(g), blue: LedColor.clamp(b))
    }

    /// Parses `{"r":..,"g":..,"b":..}` (also accepts `red`/`green`/`blue`); nil when any channel is missing.
    public init?(json: JsonValue) {
        guard let r = json["r"].doubleValue ?? json["red"].doubleValue,
              let g = json["g"].doubleValue ?? json["green"].doubleValue,
              let b = json["b"].doubleValue ?? json["blue"].doubleValue else { return nil }
        self.init(r: r, g: g, b: b)
    }

    public var json: JsonValue { ["r": .number(Double(red)), "g": .number(Double(green)), "b": .number(Double(blue))] }

    /// The single byte written to each LED characteristic.
    public func data(for channel: Channel) -> Data {
        switch channel {
        case .red: return Data([red])
        case .green: return Data([green])
        case .blue: return Data([blue])
        }
    }

    public enum Channel: CaseIterable {
        case red, green, blue

        public var characteristicUUID: String {
            switch self {
            case .red: return ParticleUUIDs.redLED
            case .green: return ParticleUUIDs.greenLED
            case .blue: return ParticleUUIDs.blueLED
            }
        }
    }

    static func clamp(_ v: Double) -> UInt8 {
        guard v.isFinite else { return 0 }
        return UInt8(max(0, min(255, v.rounded())))
    }
}
