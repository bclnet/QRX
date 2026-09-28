//
//  GlyphFactory.swift
//  QRX
//
//  Builds the SceneKit node shown on a tracked code: a plane sized by the
//  glyph's `size:` rule whose material is either a video scene or a SwiftUI
//  view hosted off screen. The SwiftUI content is
//  `GlyphContentView`, which renders `_ui` glyphs with JsonUI.
//

import ARKit
import SceneKit
import SpriteKit
import SwiftUI
import AVFoundation
import QRXCore
import JsonUI
import JsonScene

@MainActor
final class GlyphFactory {
    weak var parent: UIView?
    private let model: AppModel
    private var hosts: [String: UIHostingController<AnyView>] = [:]
    private var players: [String: AVPlayer] = [:]
    private var loopObservers: [String: NSObjectProtocol] = [:]
    /// JsonScene scenes standing on their codes, keyed by barcode id, with the JsonUI model owning their state.
    private var scenes: [String: (model: JsonUIModel, controller: JsonSceneController)] = [:]

    init(model: AppModel) {
        self.model = model
        model.heardHandlers.append { [weak self] text in self?.heard(text) }
    }

    /// Recognised speech goes to every scene as a `spoken` event.
    func heard(_ text: String) {
        for (_, scene) in scenes { scene.controller.heard(text) }
    }

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
        case .ui(let json) where json.root.type == SceneDocument.nodeType:
            // A JsonScene scene: the actors stand on the code (the anchor's +Y is the code's normal),
            // and the plane below them only shows the glyph outline.
            geometry.firstMaterial?.diffuse.contents = UIColor.white.withAlphaComponent(0.15)
            node.addChildNode(scene(for: json, key: result.barcode.id).stage)
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

    /// Builds (or reuses) the scene controller for a `_ui` document whose root is a `Scene`.
    private func scene(for document: JsonDocument, key: String) -> JsonSceneController {
        if let existing = scenes[key] { return existing.controller }
        let ui = JsonUIModel(document: document)
        ui.runtime.actions.fallback = { [weak model] name, args, context in model?.actions.invoke(name, args: args, context: context) }
        let controller = JsonSceneController(document: SceneDocument(document: document) ?? SceneDocument(), context: ui.runtime.context, mindProvider: model.ai.provider)
        controller.onIssue = { [weak model] message in Task { @MainActor in model?.showToast(message) } }
        scenes[key] = (ui, controller)
        return controller
    }

    /// Called every frame by the AR view so mobile actors move and minds sense the viewer.
    func update(time: TimeInterval, pointOfView: SCNNode?) {
        for (_, scene) in scenes {
            let viewer = pointOfView.map { scene.controller.stagePoint(fromWorld: $0.worldPosition) }
            let forward = pointOfView.map { pov -> Vec3 in
                let f = pov.worldFront
                return Vec3(Double(f.x), Double(f.y), Double(f.z))
            }
            scene.controller.update(time: time, viewer: viewer, viewerForward: forward)
        }
    }

    /// Routes a tap on a SceneKit node to the scene it belongs to; returns false when no scene was hit.
    @discardableResult
    func tap(on node: SCNNode) -> Bool {
        var current: SCNNode? = node
        while let n = current {
            if let scene = scenes.values.first(where: { $0.controller.stage === n }) {
                scene.controller.tap(on: node)
                return true
            }
            current = n.parent
        }
        return false
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
