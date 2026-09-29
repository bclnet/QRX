/*
 * BlueUUIDs.kt
 * QRX
 *
 * GATT identifiers of the QRX BLUE/1.0 service (mirror of BlueUUIDs.swift).
 */
package com.bclnet.qrx.core.blue

import java.util.UUID

object BlueUUIDs {
    /** QRX BLUE/1.0 service. */
    val SERVICE: UUID = UUID.fromString("b4250500-fb4b-4746-b2b0-93f0e61122c6")
    /** Clients write request chunks here. */
    val REQUEST: UUID = UUID.fromString("b4250501-fb4b-4746-b2b0-93f0e61122c6")
    /** The server notifies response chunks here. */
    val RESPONSE: UUID = UUID.fromString("b4250502-fb4b-4746-b2b0-93f0e61122c6")
    /** Client Characteristic Configuration descriptor (enables notifications). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The local name advertised by a QRX server. */
    const val DEFAULT_LOCAL_NAME = "QRX"
}
