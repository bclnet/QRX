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
        // The glyph planes show SwiftUI views (forms, buttons) that take their own touches. This recognizer
        // is only for JsonScene actors, so it listens without holding back or cancelling those touches.
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:)))
        tap.cancelsTouchesInView = false
        tap.delaysTouchesEnded = false
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)
        UIApplication.shared.isIdleTimerDisabled = true
        return view
    }

    func updateUIView(_ uiView: ARSCNView, context: Context) {
        context.coordinator.forgetIfAsked()
    }

    static func dismantleUIView(_ uiView: ARSCNView, coordinator: Coordinator) {
        uiView.session.pause()
        coordinator.factory.reset()
        UIApplication.shared.isIdleTimerDisabled = false
    }

    final class Coordinator: NSObject, ARSCNViewDelegate, BarcodeDetectorDelegate, UIGestureRecognizerDelegate {
        let model: AppModel
        let detector = BarcodeDetector()
        let factory: GlyphFactory
        weak var view: ARSCNView?
        /// The model's `forgetCount` this view last acted on.
        private var forgetCount: Int

        @MainActor
        init(model: AppModel) {
            self.model = model
            forgetCount = model.forgetCount
            factory = GlyphFactory(model: model)
            super.init()
            detector.delegate = self
            detector.resolver = { [weak model] barcode, completion in
                Task { @MainActor in model?.resolve(barcode, completion: completion) }
            }
        }

        /// Restarts tracking with no images when "Forget" was pressed since the last look. The model says so
        /// itself: its glyph list is also empty for a moment whenever the detector has just seen a new code.
        @MainActor
        func forgetIfAsked() {
            guard forgetCount != model.forgetCount else { return }
            forgetCount = model.forgetCount
            detector.reset()
            factory.reset()
            run(with: [], options: [.resetTracking, .removeExistingAnchors])
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

        func barcodeDetector(_ detector: BarcodeDetector, resolved result: BarcodeResult) {
            // On main, in the same turn the result changed: a node built before this is rebuilt here,
            // and one built after it already sees the document.
            let factory = self.factory
            if Thread.isMainThread { MainActor.assumeIsolated { factory.refresh(result) } }
            else { Task { @MainActor in factory.refresh(result) } }
        }

        func renderer(_ renderer: SCNSceneRenderer, nodeFor anchor: ARAnchor) -> SCNNode? {
            guard let imageAnchor = anchor as? ARImageAnchor else { return nil }
            // SceneKit may ask from its render thread; the factory builds UIKit views, so hop to main and wait.
            // The result is read there too, so it cannot go stale between the read and the build.
            let factory = self.factory, detector = self.detector
            let build: @MainActor () -> SCNNode? = {
                detector.result(for: imageAnchor.name).map { factory.node(for: imageAnchor, result: $0) }
            }
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

        // MARK: Session state (ARSCNView passes these on to its delegate)

        func session(_ session: ARSession, didFailWithError error: Error) {
            let message = Coordinator.message(forFailure: error)
            Task { @MainActor in self.model.sessionFailed(message) }
        }

        func sessionWasInterrupted(_ session: ARSession) {
            Task { @MainActor in self.model.sessionInterrupted() }
        }

        func sessionInterruptionEnded(_ session: ARSession) {
            Task { @MainActor in self.model.sessionInterruptionEnded() }
        }

        /// What the chrome says when the session fails; without camera access the view is only black.
        static func message(forFailure error: Error) -> String {
            if (error as? ARError)?.code == .cameraUnauthorized { return "QRX needs the camera. Allow it in Settings, under QRX." }
            return "AR stopped: \(error.localizedDescription)"
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
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
