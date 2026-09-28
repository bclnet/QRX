//
//  ARGlyphView.swift
//  QRX
//
//  The ARKit scene: runs an image tracking session whose reference images
//  are the QR codes found by BarcodeDetector, and asks GlyphFactory for a
//  node when a tracked code appears.
//

import SwiftUI
import ARKit
import SceneKit

struct ARGlyphView: UIViewRepresentable {
    @EnvironmentObject private var model: AppModel

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> ARSCNView {
        let view = ARSCNView(frame: .zero)
        view.automaticallyUpdatesLighting = true
        view.delegate = context.coordinator
        view.session.delegate = context.coordinator.detector
        context.coordinator.view = view
        context.coordinator.factory.parent = view
        context.coordinator.run(with: [], options: [.resetTracking, .removeExistingAnchors])
        view.addGestureRecognizer(UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:))))
        UIApplication.shared.isIdleTimerDisabled = true
        return view
    }

    func updateUIView(_ uiView: ARSCNView, context: Context) {
        if model.foundGlyphs.isEmpty && !context.coordinator.detector.found.isEmpty {
            // "Forget" was pressed: restart tracking with no images.
            context.coordinator.detector.reset()
            context.coordinator.run(with: [], options: [.resetTracking, .removeExistingAnchors])
        }
    }

    static func dismantleUIView(_ uiView: ARSCNView, coordinator: Coordinator) {
        uiView.session.pause()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    final class Coordinator: NSObject, ARSCNViewDelegate, BarcodeDetectorDelegate {
        let model: AppModel
        let detector = BarcodeDetector()
        let factory: GlyphFactory
        weak var view: ARSCNView?

        @MainActor
        init(model: AppModel) {
            self.model = model
            factory = GlyphFactory(model: model)
            super.init()
            detector.delegate = self
            detector.resolver = { [weak model] barcode, completion in
                Task { @MainActor in model?.resolve(barcode, completion: completion) }
            }
        }

        func run(with images: Set<ARReferenceImage>, options: ARSession.RunOptions = [.removeExistingAnchors]) {
            let configuration = ARImageTrackingConfiguration()
            configuration.trackingImages = images
            configuration.maximumNumberOfTrackedImages = max(1, images.count)
            view?.session.run(configuration, options: options)
        }

        func barcodeDetector(_ detector: BarcodeDetector, updated trackingImages: Set<ARReferenceImage>) {
            DispatchQueue.main.async { self.run(with: trackingImages) }
        }

        func renderer(_ renderer: SCNSceneRenderer, nodeFor anchor: ARAnchor) -> SCNNode? {
            guard let imageAnchor = anchor as? ARImageAnchor, let result = detector.result(for: imageAnchor.name) else { return nil }
            // SceneKit may ask from its render thread; the factory builds UIKit views, so hop to main and wait.
            let factory = self.factory
            let build: @MainActor () -> SCNNode = { factory.node(for: imageAnchor, result: result) }
            if Thread.isMainThread { return MainActor.assumeIsolated(build) }
            return DispatchQueue.main.sync { MainActor.assumeIsolated(build) }
        }

        func renderer(_ renderer: SCNSceneRenderer, didUpdate node: SCNNode, for anchor: ARAnchor) {
            guard let imageAnchor = anchor as? ARImageAnchor else { return }
            node.isHidden = !imageAnchor.isTracked
        }

        func renderer(_ renderer: SCNSceneRenderer, updateAtTime time: TimeInterval) {
            let pointOfView = renderer.pointOfView
            Task { @MainActor in self.factory.update(time: time, pointOfView: pointOfView) }
        }

        /// Taps on a JsonScene actor reach its scene; the SwiftUI planes handle their own touches.
        @objc func tap(_ recognizer: UITapGestureRecognizer) {
            guard let view = view else { return }
            let point = recognizer.location(in: view)
            guard let hit = view.hitTest(point, options: [.boundingBoxOnly: true]).first else { return }
            Task { @MainActor in self.factory.tap(on: hit.node) }
        }
    }
}
