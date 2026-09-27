//
//  GlyphFactory.swift
//  QRX
//
//  Builds the SceneKit node shown on a tracked code: a plane sized by the
//  glyph's `size:` rule whose material is either a video scene or a SwiftUI
//  view hosted off screen (Glyph's approach). The SwiftUI content is
//  `GlyphContentView`, which renders `_ui` glyphs with JsonUI.
//

import ARKit
import SceneKit
import SpriteKit
import SwiftUI
import AVFoundation
import QRXCore

@MainActor
final class GlyphFactory {
    weak var parent: UIView?
    private let model: AppModel
    private var hosts: [String: UIHostingController<AnyView>] = [:]
    private var players: [String: AVPlayer] = [:]
    private var loopObservers: [String: NSObjectProtocol] = [:]

    init(model: AppModel) { self.model = model }

    func node(for anchor: ARImageAnchor, result: BarcodeResult) -> SCNNode {
        let physical = anchor.referenceImage.physicalSize
        let size = result.barcode.size(for: .normal)
        let plane = GlyphPlane(size: size, physicalWidth: physical.width, physicalHeight: physical.height)
        let geometry = SCNPlane(width: CGFloat(plane.width), height: CGFloat(plane.height))
        geometry.cornerRadius = CGFloat(min(plane.width, plane.height)) * 0.05

        let planeNode = SCNNode(geometry: geometry)
        planeNode.eulerAngles.x = -.pi / 2
        planeNode.position = SCNVector3(Float(plane.offsetX), 0.001, Float(-plane.offsetY))
        let node = SCNNode()
        node.addChildNode(planeNode)

        let pixelWidth = Int(max(256, min(1024, plane.width * 4000)))
        let pixelHeight = Int(Double(pixelWidth) * plane.height / max(plane.width, 0.001))
        switch result.document?.content {
        case .video(let url, let loop):
            attachVideo(url: url, loop: loop, to: geometry, key: result.barcode.id, size: CGSize(width: pixelWidth, height: pixelHeight))
        case nil:
            geometry.firstMaterial?.diffuse.contents = UIColor.white.withAlphaComponent(0.35)
            attach(view: AnyView(GlyphPlaceholderView(text: result.barcode.id)), to: geometry, key: result.barcode.id, size: CGSize(width: pixelWidth, height: pixelHeight))
        case .some(let content):
            let document = result.document!
            attach(view: AnyView(GlyphContentView(document: document, content: content).environmentObject(model)), to: geometry, key: result.barcode.id, size: CGSize(width: pixelWidth, height: pixelHeight))
        }
        return node
    }

    private func attach(view: AnyView, to plane: SCNPlane, key: String, size: CGSize) {
        guard let parent = parent else { return }
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.isOpaque = false
        host.view.backgroundColor = .clear
        // The hosting view must be in a window to render; keep it behind the AR view.
        parent.insertSubview(host.view, at: 0)
        hosts[key]?.view.removeFromSuperview()
        hosts[key] = host
        let material = SCNMaterial()
        material.diffuse.contents = host.view
        material.isDoubleSided = true
        plane.materials = [material]
    }

    private func attachVideo(url: URL, loop: Bool, to plane: SCNPlane, key: String, size: CGSize) {
        let player = AVPlayer(playerItem: AVPlayerItem(url: url))
        let videoNode = SKVideoNode(avPlayer: player)
        let scene = SKScene(size: size)
        videoNode.position = CGPoint(x: size.width / 2, y: size.height / 2)
        videoNode.size = size
        videoNode.yScale = -1
        scene.addChild(videoNode)
        plane.firstMaterial?.diffuse.contents = scene
        players[key]?.pause()
        players[key] = player
        if let observer = loopObservers[key] { NotificationCenter.default.removeObserver(observer) }
        if loop {
            loopObservers[key] = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: player.currentItem, queue: .main) { _ in
                player.seek(to: .zero)
                player.play()
            }
        }
        player.play()
    }
}

struct GlyphPlaceholderView: View {
    let text: String
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24).fill(Color.white.opacity(0.85))
            VStack(spacing: 8) {
                ProgressView()
                Text(text).font(.caption).lineLimit(3).multilineTextAlignment(.center).padding(.horizontal)
            }
        }
    }
}
