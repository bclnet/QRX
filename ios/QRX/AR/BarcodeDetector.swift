//
//  BarcodeDetector.swift
//  QRX
//
//  Detects QR codes in ARKit frames with Vision, turns each new code into an
//  ARReferenceImage for image tracking and resolves its glyph document.
//  Lookups go through AppModel/GlyphLookup.
//

import ARKit
import Vision
import CoreImage
import QRXCore

protocol BarcodeDetectorDelegate: AnyObject {
    func barcodeDetector(_ detector: BarcodeDetector, updated trackingImages: Set<ARReferenceImage>)
}

struct BarcodeResult {
    var version: Int
    let referenceImage: ARReferenceImage
    let barcode: GlyphBarcode
    var document: GlyphDocument?
}

final class BarcodeDetector: NSObject {
    weak var delegate: BarcodeDetectorDelegate?
    /// Resolves a payload into a document (AppModel.resolve).
    var resolver: ((GlyphBarcode, @escaping (GlyphDocument?) -> Void) -> Void)?

    /// Tracked codes by payload.
    private(set) var found: [String: BarcodeResult] = [:]
    private let lock = NSLock()
    private var currentBuffer: CVPixelBuffer?
    private let visionQueue = DispatchQueue(label: "net.bcl.qrx.vision")
    private let perspectiveTransform = CIFilter(name: "CIPerspectiveCorrection")!
    private var version = 0
    /// Physical width assumed for a detected code, in metres.
    var assumedPhysicalWidth: CGFloat = 0.06

    private lazy var barcodeRequest: VNDetectBarcodesRequest = {
        let request = VNDetectBarcodesRequest { [weak self] request, error in self?.processBarcodes(for: request, error: error) }
        request.symbologies = [.qr]
        return request
    }()

    func result(for payload: String?) -> BarcodeResult? {
        guard let payload = payload else { return nil }
        lock.lock(); defer { lock.unlock() }
        return found[payload]
    }

    func reset() {
        lock.lock(); found.removeAll(); lock.unlock()
    }

    private func detectCurrentImage() {
        guard let buffer = currentBuffer else { return }
        version &+= 1
        let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
        visionQueue.async {
            defer { self.currentBuffer = nil }
            do { try handler.perform([self.barcodeRequest]) }
            catch { print("QRX: vision request failed: \(error)") }
        }
    }

    private func processBarcodes(for request: VNRequest, error: Error?) {
        guard let results = request.results as? [VNBarcodeObservation] else { return }
        var changed = false
        for result in results {
            guard let payload = result.payloadStringValue, !payload.isEmpty else { continue }
            lock.lock()
            if var existing = found[payload] {
                existing.version = version
                found[payload] = existing
                lock.unlock()
                continue
            }
            lock.unlock()
            guard let image = extractImage(for: result), let referenceImage = createReferenceImage(image: image, name: payload) else { continue }
            let barcode = GlyphBarcode(payload: payload)
            lock.lock()
            found[payload] = BarcodeResult(version: version, referenceImage: referenceImage, barcode: barcode, document: nil)
            lock.unlock()
            changed = true
            DispatchQueue.main.async {
                self.resolver?(barcode) { document in
                    self.lock.lock()
                    if var entry = self.found[payload] { entry.document = document; self.found[payload] = entry }
                    self.lock.unlock()
                }
            }
        }
        if changed {
            lock.lock()
            let images = Set(found.values.map { $0.referenceImage })
            lock.unlock()
            delegate?.barcodeDetector(self, updated: images)
        }
    }

    private func createReferenceImage(image: CIImage, name: String) -> ARReferenceImage? {
        guard let pixelBuffer = image.toPixelBuffer(pixelFormat: kCVPixelFormatType_32BGRA) else { return nil }
        let referenceImage = ARReferenceImage(pixelBuffer, orientation: .up, physicalWidth: assumedPhysicalWidth)
        referenceImage.name = name
        return referenceImage
    }

    /// Crops and perspective-corrects the code out of the frame so ARKit can track it.
    private func extractImage(for result: VNBarcodeObservation) -> CIImage? {
        guard let buffer = currentBuffer else { return nil }
        let width = CGFloat(CVPixelBufferGetWidth(buffer)), height = CGFloat(CVPixelBufferGetHeight(buffer))
        func point(_ p: CGPoint) -> CIVector { CIVector(cgPoint: CGPoint(x: p.x * width, y: p.y * height)) }
        perspectiveTransform.setValue(point(result.topLeft), forKey: "inputTopLeft")
        perspectiveTransform.setValue(point(result.topRight), forKey: "inputTopRight")
        perspectiveTransform.setValue(point(result.bottomLeft), forKey: "inputBottomLeft")
        perspectiveTransform.setValue(point(result.bottomRight), forKey: "inputBottomRight")
        perspectiveTransform.setValue(CIImage(cvPixelBuffer: buffer).oriented(.up), forKey: kCIInputImageKey)
        return perspectiveTransform.value(forKey: kCIOutputImageKey) as? CIImage
    }
}

extension BarcodeDetector: ARSessionDelegate {
    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        guard currentBuffer == nil, case .normal = frame.camera.trackingState else { return }
        currentBuffer = frame.capturedImage
        detectCurrentImage()
    }
}

extension CIImage {
    /// Renders the image into a new pixel buffer.
    func toPixelBuffer(pixelFormat: OSType) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        let options: [String: Any] = [
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
        ]
        let status = CVPixelBufferCreate(kCFAllocatorDefault, Int(extent.width), Int(extent.height), pixelFormat, options as CFDictionary, &buffer)
        guard status == kCVReturnSuccess, let pixelBuffer = buffer else { return nil }
        let context = MTLCreateSystemDefaultDevice().map { CIContext(mtlDevice: $0) } ?? CIContext()
        context.render(self, to: pixelBuffer)
        return pixelBuffer
    }
}
