//
//  BlueUUIDs.swift
//  QRX
//
//  GATT identifiers of the QRX BLUE/1.0 service.
//

import Foundation

public enum BlueUUIDs {
    /// QRX BLUE/1.0 service.
    public static let service = "b4250500-fb4b-4746-b2b0-93f0e61122c6"
    /// Clients write request chunks here.
    public static let request = "b4250501-fb4b-4746-b2b0-93f0e61122c6"
    /// The server notifies response chunks here.
    public static let response = "b4250502-fb4b-4746-b2b0-93f0e61122c6"

    /// The local name advertised by a QRX server.
    public static let defaultLocalName = "QRX"
}
