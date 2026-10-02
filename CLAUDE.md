# QRX

The app that ties the family together: it scans QR codes carrying "glyphs",
resolves them to JsonUI documents and places the content in the room with
AR. Three targets: iOS (SwiftUI + ARKit), Android phone (Compose + CameraX +
ML Kit), Meta Quest (Meta Spatial SDK). Glyph content can be a JsonUI form, a
web view, an image, a video, or a JsonScene with actors whose minds get
tokens through TokenX.

## Naming

- A **glyph** is the whole unit a user makes and shares: the QR code, its payload header
  (size, flags, URL), the resolved document and its placement. Types follow that:
  `GlyphBarcode`, `GlyphDocument`, `GlyphSize`, `GlyphPlacement`, `GlyphLookup`, `FoundGlyph`.
  Keep the word for anything about what a code carries and shows.
- A **marker** is the tracking side: a pattern the camera finds and derives a pose from.
  Use it (and "marker pose") for pose estimation and anchoring, never for content.
- **code** on its own means the QR code, or the `code` anchor kind in a JsonScene. Do not
  name types after it (`CodeDocument` would read as source code).

Docs: `docs/GLYPH.md` (glyph and lookup format), `docs/BLUE.md` (the BLUE/1.0
Bluetooth protocol for sharing glyphs between devices), `docs/ARCHITECTURE.md`.
Examples in `examples/` (`ui-login.json` uses fragments from `shared/`,
`scene-bush.json` pulls a body and a mind by URL).

## Dependencies

| library | iOS | Android |
| --- | --- | --- |
| JsonUI | Swift package by URL (master) | includeBuild `third_party/JsonUI/android` as "JsonUI" |
| JsonScene | JsonScene product | jsonscene-compose (phone), jsonscene-spatial (Quest) |
| JsonMind | JsonMindTokenX product | jsonmind-tokenx |
| TokenX | TokenX, TokenXApple, TokenXUI | tokenx-compose (brings tokenx-android) |

Submodules under `third_party/` are what the Gradle composite build uses; the
iOS app resolves the same repos through SwiftPM (`ios/Package.resolved` pins
them). Bump a submodule and the pins together when a library changes API.

## Layout

```
Package.swift, ios/Sources/QRXCore     glyph parsing, BLUE protocol, GlyphLookup (fragment resolution, max 16 documents);
                                       38 tests run on Linux
ios/project.yml                        XcodeGen spec: packages, frameworks, Info.plist properties, signing team
ios/QRX.xcodeproj                      generated; `xcodegen generate` from ios/ (in this container:
                                       USER=builder LOGNAME=builder /opt/toolchains/xcodegen-src/.build/release/xcodegen generate)
ios/QRX                                AppModel, ContentView (chrome, toasts, KeyboardBar, settings sheet),
                                       AR/ (ARGlyphView, BarcodeDetector, GlyphFactory, GlyphPlane), Bluetooth/,
                                       Services/ (AIService = TokenXModel + TokenXMindProvider, SpeechInput, AppServices),
                                       Views/ (ChromeView with push-to-talk, GlyphContentView, SettingsView)
ios/QRXTests                           16 app tests, run in the simulator (AppModel, GlyphFactory, GlyphPlane, SpeechInput, GATT flow)
android/qrx-core                       Kotlin mirror of QRXCore with tests
android/qrx-shared                     GlyphSession, scan/ (CameraX + ML Kit), ui/ (GlyphContent, SettingsSheet, ChromeBar),
                                       blue/ (GATT server, client, permissions), ai/AiService, speech/SpeechInput
android/app                            phone app (applicationId com.bclnet.qrx, minSdk 28)
android/quest                          Quest app (Meta Spatial SDK, targetSdk 32): QuestActivity, QuestCamera, QuestPanels,
                                       QrPoseEstimator
```

## Build and test

```
swift test                                             # QRXCore, from the repo root (Package.swift is there); also runs on Linux.
                                                       # It leaves an untracked Package.resolved at the root: delete it.
cd android && ./gradlew build                          # 43 JVM tests (32 core, 6 shared, 5 quest), app-debug.apk and quest-debug.apk
                                                       # under */build/outputs/apk/debug; needs the third_party submodules
                                                       # (`git submodule update --init`, also in a fresh worktree)
cd ios && xcodegen generate                            # after editing project.yml
xcodebuild -project ios/QRX.xcodeproj -scheme QRX -destination 'generic/platform=iOS Simulator' build   # Mac only
xcodebuild test -project ios/QRX.xcodeproj -scheme QRX -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath ios/build/DerivedData CODE_SIGNING_ALLOWED=NO                                         # app tests, Mac only
xcodebuild build -project ios/QRX.xcodeproj -scheme QRX -destination 'generic/platform=iOS' -derivedDataPath ios/build/DerivedData
xcrun devicectl device install app --device <udid> ios/build/DerivedData/Build/Products/Debug-iphoneos/QRX.app
xcrun devicectl device process launch --device <udid> net.bcl.qrx                                        # phone must be unlocked
```

The iOS app only builds on a Mac. In a Linux session, parse-check edited app
files with `swiftc -parse` and leave the Xcode build to the owner or CI.
Install APKs with `adb install -r`; the Quest needs developer mode.

## Conventions

- `Info.plist` is generated by XcodeGen from `info.properties` in `project.yml`. Never edit
  the plist by hand; a regenerate wipes it (that is how the camera usage description got
  lost once). Same for the signing team: it is in `project.yml` under `DEVELOPMENT_TEAM`.
- AI settings UI comes from TokenX (`TokenXSettingsSection` / `TokenXSettings`); the app only
  wraps a `TokenXModel` with app id `net.bcl.qrx` and hands `TokenXMindProvider` to scenes.
- Speech is the app's: SFSpeechRecognizer on iOS, SpeechRecognizer on Android, push-to-talk
  in the chrome bar; the transcript reaches scenes as a `spoken` event through `heard`.
- Permissions: camera, Bluetooth, microphone, speech, local network on iOS; CAMERA,
  BLUETOOTH_*, RECORD_AUDIO on Android (`BluePermissions.required`).
- Fragment overrides are shallow; tests must not assume a deep merge.
- `extras/` holds code that is not compiled: worked examples kept for reference (the Particle
  LED board client that drove the Bluetooth layer). Nothing under it may be referenced from
  the apps, the docs or the settings screens.

## How the iOS app fits together

- Lookup: `GlyphLookup` fetches only `http(s)` and `blue` URLs, for the document and for its `$ref`
  fragments; anything else (`file:`, a relative reference with no base) fails without a request.
  A payload's `url:` header takes any text, so the scheme check is in the lookup, not the parser.
  A request in flight holds the lookup until it answers.
- Payload: a body that is only a URL line is the location (size, blank line, URL is a valid layout).
  An inline document after only `size:` lines is the body.
- A code is registered for tracking as soon as it is seen; its document arrives later. The detector
  then calls `barcodeDetector(_:resolved:)` and `GlyphFactory.refresh` refills the node ARKit already
  holds. A failed lookup carries its reason (`BarcodeResult.error`) onto the placeholder card.
- "Forget" is `AppModel.forgetCount`; `ARGlyphView.Coordinator.forgetIfAsked` resets the detector and
  `GlyphFactory.reset()` (hosted views, players, scenes). Do not infer it from `foundGlyphs` being
  empty: that is also true for a moment on every new code.
- The SwiftUI views on glyph planes (forms, buttons) are SceneKit materials and take their own touches.
  Any gesture recognizer on the AR view must not cancel or delay touches (`cancelsTouchesInView = false`),
  or forms stop responding; that is how it broke when the JsonScene tap recognizer was added.
- Typing: `KeyboardBar` in `ContentView` shows Done above the keyboard whenever it is up. Fields live
  on a plane, so there is no other way to give the keyboard up.
- The chrome and settings read speech and Bluetooth state through `AppModel`, which re-publishes
  their changes. A new service whose state the chrome shows needs the same forwarding.
- Speech switches the audio session to record-only while listening and puts back what it found when
  listening stops; `GlyphFactory.resumeVideo()` then restarts the video players.
- BLUE: `BlueAssembler.append` returns every message a chunk completed. `BlueOutbox` keeps response
  chunks per client; the GATT server notifies only the central that asked.
- Android mirrors the core fixes (`GlyphSize`, the fetch scheme check, the URL-after-a-blank-line rule,
  `BlueAssembler` returning several messages) and the GATT client stops its scan when a request ends.
  The rest is iOS only by nature: the Android UI is Compose state, so a glyph fills in and shows its
  error on its own; its GATT server already queued per device; SpeechRecognizer does not take the
  audio session; forms are ordinary views with the system keyboard.

## Gotchas

- The iOS Simulator has no camera, so no code is ever tracked, and it cannot draw a SwiftUI view as a
  SceneKit material at all (Metal aborts in `SCNTextureCoreAnimationSource`). Glyph planes, touch on
  forms and anything ARKit need a phone. `GlyphFactory.node(physical:result:)` is the seam the
  simulator tests use; they never render.
- `xcodegen` is not installed on the owner's Mac. Test files added since were put into
  `project.pbxproj` by hand; a regenerate picks them up from their folders.

- `android/settings.gradle.kts` includes the four library builds with explicit names; the
  libraries skip their own includes when they have a parent build.
- Maven Central can rate-limit (429) in CI; retry rather than change versions.
- Local `master` in a checkout may be stale; work was pushed from `claude/qrx-apps`.
