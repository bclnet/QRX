# BLUE/1.0 over Bluetooth LE

QRX's Bluetooth layer has two halves: a client for
a Particle board (three LED characteristics and a battery level) and an
HTTP-like text protocol called `BLUE/1.0` with an unfinished line parser. QRX
implements both on every platform.

## Transport

QRX devices expose one GATT service; a device that runs the *server* can hand
glyph documents to devices that run the *client*, so glyphs work with no
network. UUIDs continue the Particle example numbering.

| | UUID |
| --- | --- |
| QRX service | `b4250500-fb4b-4746-b2b0-93f0e61122c6` |
| request characteristic (write, write without response) | `b4250501-fb4b-4746-b2b0-93f0e61122c6` |
| response characteristic (notify, read) | `b4250502-fb4b-4746-b2b0-93f0e61122c6` |

Messages are UTF-8 text framed with a 4-byte little-endian length prefix and
split into chunks no larger than the negotiated MTU minus 3 bytes (20 bytes
when the MTU is the default 23). The receiver concatenates chunks until the
announced length is complete. A client writes the request chunks to the
request characteristic; the server notifies the response chunks on the
response characteristic. One request is in flight per connection.

## Messages

Request:

```
GET /glyph/ui-login BLUE/1.0
Accept: application/json

```

Response:

```
BLUE/1.0 200 OK
Content-Type: application/json
Content-Length: 812

{ "_ui": ... }
```

* Request line: `METHOD uri BLUE/1.0`. Methods are `GET` and `POST`.
* Header lines `Name: value` until an empty line; the body follows. `Content-Length` is added by the framer when a body is present.
* Status line: `BLUE/1.0 status description`. Statuses follow HTTP: 200, 400, 404, 500.

## Routes served by QRX

| route | response |
| --- | --- |
| `GET /glyphs` | JSON array of the names of the glyph documents the device shares |
| `GET /glyph/{name}` | the glyph document |
| `POST /glyph/{name}` | stores the request body as a shared glyph document |
| `GET /led` | `{ "r": 0, "g": 0, "b": 0 }`, the colour last written to the Particle board |
| `POST /led` | body `{ "r": 255, "g": 0, "b": 0 }`; writes the colour to the Particle board when one is connected |
| `GET /battery` | `{ "level": 87 }` from the Particle board, or 503 when none is connected |
| `GET /ping` | `pong` |

A QR payload with the URL `blue://<device name>/glyph/<name>` makes the app
connect to the peripheral advertising the QRX service with that local name
(or the first one found when the name is `*`) and issue `GET /glyph/<name>`.

## Particle LED board

The client for the Particle LED example firmware is kept as
is, and finished:

| | UUID |
| --- | --- |
| LED service | `b4250400-fb4b-4746-b2b0-93f0e61122c6` |
| red / green / blue (write without response, one byte 0–255) | `b4250401` / `b4250402` / `b4250403` `-fb4b-4746-b2b0-93f0e61122c6` |
| battery service / level (notify) | `180f` / `2a19` |

The app scans for the LED service, connects to the first board found, exposes
sliders for the three channels and shows the battery level; the `led` host
action of `_ui` glyphs and the `/led` route write through the same client.

## Platform notes

* **iOS** uses CoreBluetooth: `CBCentralManager` for the client and LED board, `CBPeripheralManager` for the server. `NSBluetoothAlwaysUsageDescription` is set in `Info.plist`.
* **Android** uses `BluetoothLeScanner` / `BluetoothGatt` for the client and `BluetoothGattServer` + `BluetoothLeAdvertiser` for the server. Runtime permissions `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` (API 31+) or `ACCESS_FINE_LOCATION` (older) are requested by the shared `BluePermissions` helper.
* **Meta Quest 3** runs the same Android implementation. Horizon OS exposes the Android Bluetooth APIs to apps; when the adapter is unavailable the app shows the service as unavailable instead of failing.
