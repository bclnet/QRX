# Particle LED board (reference only)

A BLE client for the Particle LED example firmware. It was the peripheral
the Bluetooth layer was built against and is kept here for reference; the
apps, docs and settings no longer mention it and nothing in this folder is
compiled.

```
ios/ParticleLed.swift            ParticleUUIDs and LedColor (once in QRXCore/Blue/BlueUUIDs.swift)
ios/ParticleLedClient.swift      CoreBluetooth client: scans for the LED service, writes the three colour
                                 characteristics, subscribes to the battery level
android/ParticleLed.kt           the Kotlin mirror of the UUIDs and LedColor
android/ParticleLedClient.kt     BluetoothGatt client, same behaviour
```

| | UUID |
| --- | --- |
| LED service | `b4250400-fb4b-4746-b2b0-93f0e61122c6` |
| red / green / blue (write without response, one byte 0–255) | `b4250401` / `b4250402` / `b4250403` `-fb4b-4746-b2b0-93f0e61122c6` |
| battery service / level (notify) | `180f` / `2a19` |

## How it was wired, should it come back

- `BluetoothService` owned a `led` client next to the BLUE client and server, started it
  with the others and folded `led.isConnected` into `isAnythingConnected`.
- `BlueGlyphService` had three hooks (`ledColor`, `setLedColor`, `batteryLevel`) that the
  service set from the client, and served `GET /led`, `POST /led` (`{"r":..,"g":..,"b":..}`)
  and `GET /battery` (`{"level":87}`), each 503 when no board was connected.
- The `led` host action for `_ui` glyphs parsed `LedColor(json: args)` and returned
  `{"written": bool}`.
- The settings screen showed a "Particle LED board" section: state, battery, three sliders
  (0–255, written when the slider is released) and an Off button.
