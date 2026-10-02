//
//  BlueOutbox.swift
//  QRX
//
//  The response chunks a BLUE server still has to send, kept per client: a
//  response goes only to the client that asked for it.
//

import Foundation

public struct BlueOutbox<Client: Hashable> {
    private var pending: [(client: Client, frame: Data)] = []

    public init() {}

    public var isEmpty: Bool { pending.isEmpty }

    public mutating func enqueue(_ frames: [Data], for client: Client) {
        pending.append(contentsOf: frames.map { (client, $0) })
    }

    /// Sends the queued chunks of subscribed clients, oldest first, until `send` reports that the
    /// transport is full; call again when it has room. Chunks of clients that have not subscribed
    /// stay queued for `next(for:)`.
    public mutating func flush(to subscribed: Set<Client>, send: (Client, Data) -> Bool) {
        var index = 0
        while index < pending.count {
            let (client, frame) = pending[index]
            guard subscribed.contains(client) else { index += 1; continue }
            guard send(client, frame) else { return }
            pending.remove(at: index)
        }
    }

    /// The next chunk for a client that reads instead of subscribing.
    public mutating func next(for client: Client) -> Data? {
        guard let index = pending.firstIndex(where: { $0.client == client }) else { return nil }
        return pending.remove(at: index).frame
    }

    /// Forgets what was queued for a client that went away.
    public mutating func remove(_ client: Client) {
        pending.removeAll { $0.client == client }
    }
}
