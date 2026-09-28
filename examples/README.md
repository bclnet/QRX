# QRX examples

Glyph documents that a QR code can point at. Encode the *payload* text below
into a QR code (any generator works, e.g. https://goqr.me) and point QRX at it.

| document | payload |
| --- | --- |
| [image.json](image.json) | `https://raw.githubusercontent.com/bclnet/QRX/master/examples/image.json` |
| [video.json](video.json) | `size: *2`<br>`https://raw.githubusercontent.com/bclnet/QRX/master/examples/video.json` |
| [web.json](web.json) | `size: 10l10x20b10`<br>`https://raw.githubusercontent.com/bclnet/QRX/master/examples/web.json` |
| [button.json](button.json) | `https://raw.githubusercontent.com/bclnet/QRX/master/examples/button.json` |
| [ui-login.json](ui-login.json) | `size: *3`<br>`https://raw.githubusercontent.com/bclnet/QRX/master/examples/ui-login.json` |
| [ui-survey.json](ui-survey.json) | `https://raw.githubusercontent.com/bclnet/QRX/master/examples/ui-survey.json` |
| inline | `size: *2`<br><br>`{"_button":{},"text":"Inline glyph"}` |
| Bluetooth | `blue://QRX/glyph/ui-login` (served by another QRX device, see docs/BLUE.md) |

See [docs/GLYPH.md](../docs/GLYPH.md) for the payload and document format.
