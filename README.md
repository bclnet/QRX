# QRX

Point a camera at a QR code, get a *glyph*: an image, a video, a web page, a
button or a live [JsonUI](https://github.com/bclnet/JsonUI) form anchored on
the code. QRX is the successor of [Glyph](https://github.com/bclnet/Glyph)
and ships three apps that share one format and one Bluetooth protocol.

| app | where | stack |
| --- | --- | --- |
| iOS | `ios/QRX.xcodeproj` (sources in `ios/QRX`, core package at the root `Package.swift`) | SwiftUI, ARKit image tracking + Vision, SceneKit, JsonUI, CoreBluetooth |
| Android | `android/app` | Jetpack Compose, CameraX + ML Kit, JsonUI Compose, Bluetooth LE |
| Meta Quest 3 | `android/quest` | Meta Spatial SDK 0.14 panels, Passthrough Camera API + ML Kit, JsonUI Compose, Bluetooth LE |

## How it works

1. The QR payload is a small text header (`docs/GLYPH.md`): optional `size:` rules, a URL (`https://…` or `blue://device/path`), flags, and an optional inline body.
2. The glyph document is JSON with one `_type` key: `_image`, `_avplayer`, `_web`, `_button` or `_ui`. A `_ui` document *is* a JsonUI document, so forms with state, validation scripts and host actions render natively on every platform.
3. The content is anchored on the code: ARKit tracks the code image on iOS, ML Kit re-detects it every frame on Android phones, and the Quest app estimates the code's pose from the passthrough camera and places a Spatial SDK panel in the room.

Example payload for a QR generator (`examples/README.md` has more):

```
size: *3
https://raw.githubusercontent.com/bclnet/QRX/master/examples/ui-login.json
```

## Bluetooth

The reference app stubbed a Bluetooth layer; QRX implements it on all three
platforms (`docs/BLUE.md`):

* **BLUE/1.0**, an HTTP-like text protocol over a GATT service. Every app can run the *server* (sharing its glyph documents, the LED colour and the battery level) and the *client* (used when a code says `blue://<device>/glyph/<name>`, so glyphs work with no network).
* The **Particle LED board** client from the reference app (three LED characteristics, battery notifications), with sliders in the settings panel and the `led` host action for `_ui` glyphs.

## Building

```
# core Swift package (Linux or macOS)
swift test
# iOS app: open ios/QRX.xcodeproj (regenerate with `xcodegen generate --spec ios/project.yml`)

# Android and Quest apps (needs the JsonUI submodule)
git submodule update --init
cd android && gradle build
gradle :app:installDebug          # phone
gradle :quest:installDebug        # Quest 3 in developer mode
```

The Swift package depends on JsonUI by URL; set `JSONUI_PATH=/path/to/JsonUI`
to build against a local checkout. The Android project includes JsonUI as a
Gradle composite build from the `third_party/JsonUI` submodule.

## Layout

```
Package.swift            QRXCore manifest (root, so SwiftPM can add the package by URL)
ios/Sources/QRXCore      glyph payloads, documents, lookup, BLUE/1.0 (tested on Linux)
ios/QRX, ios/QRXTests    iOS app and its tests
ios/project.yml          XcodeGen spec for ios/QRX.xcodeproj
android/qrx-core         Kotlin/JVM mirror of QRXCore with tests
android/qrx-shared       camera + ML Kit scanning, glyph composables, Bluetooth LE
android/app, quest       the phone and Quest apps
docs/                    GLYPH.md, BLUE.md, ARCHITECTURE.md
examples/                glyph documents used by the tests and bundled in the apps
third_party/JsonUI       JsonUI submodule
```

## Tests

| suite | command |
| --- | --- |
| QRXCore (Swift, 26 tests) | `swift test` |
| iOS app tests | Xcode, scheme `QRX` |
| qrx-core (26), qrx-shared (6), quest (5) | `cd android && gradle test` |

## License

MIT, see [LICENSE](LICENSE).
