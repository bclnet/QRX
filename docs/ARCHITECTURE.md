# QRX architecture

QRX: point a
camera at a QR code, read its *glyph* payload, fetch the glyph document and
show the content anchored on the code. Three apps share the same formats:

| app | directory | stack |
| --- | --- | --- |
| iOS | `ios/` (+ `Package.swift` at the root) | SwiftUI app lifecycle, ARKit image tracking + Vision QR detection, SceneKit planes, [JsonUI](https://github.com/bclnet/JsonUI) for `_ui` glyphs, CoreBluetooth |
| Android | `android/app` | Jetpack Compose, CameraX + ML Kit barcode tracking with a 2D overlay anchored on the code, JsonUI Compose, Android Bluetooth LE |
| Meta Quest 3 | `android/quest` | Meta Spatial SDK panels placed on the codes MRUK tracks, Passthrough Camera API (Camera2) + ML Kit as the fallback, JsonUI Compose panels, Android Bluetooth LE |

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

* **Core** (`ios/Sources/QRXCore`, `android/qrx-core`): the layers above, plus the BLUE/1.0 protocol (requests, responses, chunk framer, router). Pure Swift / Kotlin, unit tested on Linux and the JVM. Mirrors of each other, file for file.
* **Shared Android** (`android/qrx-shared`): camera + ML Kit scanning (`GlyphScanner`), the glyph content composables (`GlyphContent`), the Bluetooth service (`BlueGattServer`, `BlueGattClient`, `BluePermissions`) and the settings panel. Used by both the phone and the Quest app.
* **iOS app** (`ios/QRX`): `ARGlyphView` (ARKit session, `BarcodeDetector`, `GlyphFactory` placing SceneKit planes with SwiftUI content), `GlyphContentView`, `ChromeView`, and `BluetoothService` (`BlueCentral`, `BluePeripheral`).

## Anchoring

* iOS: the detected code image becomes an `ARReferenceImage`, ARKit tracks it, and a plane sized by the glyph's `size:` is attached to the anchor.
* Android phones re-detect the code every frame with ML Kit and place the content over its bounding box with the `size:` rules applied in image space (`GlyphPlacement`, tested). This needs no ARCore and works on every device with a camera.
* Quest 3 lets the headset track the codes: MRUK's QR code tracker (`Tracker.QrCode`, spatial data permission) gives each code an entity with its 6DoF world pose and payload, and the app puts a Spatial SDK panel on it (off the surface on a wall, standing on the code on a table). Codes the tracker cannot read (QR versions above 10) fall back to the passthrough camera scan: the pose is estimated from the corner points, the camera intrinsics and a 6 cm nominal code size (`QrPoseEstimator`, tested) and converted with the head pose. Panels are grabbable so they can be repositioned.

## Bluetooth

See [BLUE.md](BLUE.md). Each app runs a client (used by `blue://` glyphs)
and can run the server that shares its glyph documents.

## Testing

| suite | runs on |
| --- | --- |
| `swift test` (QRXCore: sizes, payloads, documents, protocol, framer, router, lookup) | Linux, macOS |
| `ios/QRXTests` (GATT chunk round trips, JsonUI action wiring) | Xcode |
| `android/qrx-core` JVM tests (same coverage as QRXCore) | JVM |
| `android/qrx-shared` JVM tests (`GlyphPlacement`, Blue chunk assembly) | JVM |
| `android/quest` JVM tests (`QrPoseEstimator`) | JVM |

## AI and speech

* `AIService` (iOS) / `AiService` (Android) hold TokenX's standard `TokenXModel` (app id `net.bcl.qrx`): an SQLite store in the app's private storage, keys encrypted with a Keychain or Keystore held cipher key, and the settings the panel edits. The settings panel embeds TokenX's own section (`TokenXSettingsSection` from TokenXUI, `TokenXSettings` from tokenx-compose), so a change in what TokenX needs from the user reaches QRX by bumping the package. The services expose a `TokenXMindProvider` that every scene passes to its actors' `MindSession`s, so a bush asks TokenX for tokens without knowing the provider or model.
* `SpeechInput` is push-to-talk on the chrome bar (SFSpeechRecognizer on iOS, SpeechRecognizer on Android). The final transcript goes through the app model's `heard` to every scene as a `spoken` event, which is one of the events a mind can wake on.
