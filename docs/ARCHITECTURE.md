# QRX architecture

QRX: point a
camera at a QR code, read its *glyph* payload, fetch the glyph document and
show the content anchored on the code. Three apps share the same formats:

| app | directory | stack |
| --- | --- | --- |
| iOS | `ios/` (+ `Package.swift` at the root) | SwiftUI app lifecycle, ARKit image tracking + Vision QR detection, SceneKit planes, [JsonUI](https://github.com/bclnet/JsonUI) for `_ui` glyphs, CoreBluetooth |
| Android | `android/app` | Jetpack Compose, CameraX + ML Kit barcode tracking with a 2D overlay anchored on the code, JsonUI Compose, Android Bluetooth LE |
| Meta Quest 3 | `android/quest` | Meta Spatial SDK panels placed in the room from the QR pose, Passthrough Camera API (Camera2) + ML Kit, JsonUI Compose panels, Android Bluetooth LE |

## Layers

```
                     ┌──────────────────────────┐
  QR payload  ─────▶ │ GlyphBarcode / GlyphSize │  headers, sizes, url, body
                     └────────────┬─────────────┘
                                  ▼
                     ┌──────────────────────────┐   http(s)://  → URLSession / HttpURLConnection
                     │       GlyphLookup        │   blue://     → BLUE/1.0 over BLE (BlueClient)
                     └────────────┬─────────────┘   inline body → no fetch
                                  ▼
                     ┌──────────────────────────┐
                     │      GlyphDocument       │  image | video | web | button | ui(JsonDocument) | unknown
                     └────────────┬─────────────┘
                                  ▼
                 platform renderer anchored on the code
```

* **Core** (`ios/Sources/QRXCore`, `android/qrx-core`): the layers above, plus the BLUE/1.0 protocol (requests, responses, chunk framer, router) and the Particle LED constants. Pure Swift / Kotlin, unit tested on Linux and the JVM. Mirrors of each other, file for file.
* **Shared Android** (`android/qrx-shared`): camera + ML Kit scanning (`GlyphScanner`), the glyph content composables (`GlyphContent`), the Bluetooth service (`BlueGattServer`, `BlueGattClient`, `ParticleLedClient`, `BluePermissions`) and the settings panel. Used by both the phone and the Quest app.
* **iOS app** (`ios/QRX`): `ARGlyphView` (ARKit session, `BarcodeDetector`, `GlyphFactory` placing SceneKit planes with SwiftUI content), `GlyphContentView`, `ChromeView`, and `BluetoothService` (`BlueCentral`, `BluePeripheral`, `ParticleLedClient`).

## Anchoring

* iOS: the detected code image becomes an `ARReferenceImage`, ARKit tracks it, and a plane sized by the glyph's `size:` is attached to the anchor.
* Android phones re-detect the code every frame with ML Kit and place the content over its bounding box with the `size:` rules applied in image space (`GlyphPlacement`, tested). This needs no ARCore and works on every device with a camera.
* Quest 3 estimates the code's pose from its corner points, the camera intrinsics and a 6 cm nominal code size (`QrPoseEstimator`, tested), converts it with the head pose into a world pose, and creates a Spatial SDK panel entity there. Panels are grabbable so they can be repositioned.

## Bluetooth

See [BLUE.md](BLUE.md). Each app runs a client (used by `blue://` glyphs and
the LED board) and can run the server that shares its glyph documents.

## Testing

| suite | runs on |
| --- | --- |
| `swift test` (QRXCore: sizes, payloads, documents, protocol, framer, router, lookup) | Linux, macOS |
| `ios/QRXTests` (LED byte packing, GATT chunk round trips, JsonUI action wiring) | Xcode |
| `android/qrx-core` JVM tests (same coverage as QRXCore) | JVM |
| `android/qrx-shared` JVM tests (`GlyphPlacement`, Blue chunk assembly) | JVM |
| `android/quest` JVM tests (`QrPoseEstimator`) | JVM |
