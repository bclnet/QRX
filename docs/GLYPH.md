# Glyphs

A *glyph* is a QR code whose payload tells QRX what to show on top of the
code. The payload is a small text header; the content is a JSON *glyph
document*, either fetched from a URL or embedded inline.

## Payload

```
size: *2
size: 10l10x20b10:active
multi
Some-Header: value
https://example.com/glyphs/menu.json

{ "_button": {}, "text": "inline body, used when there is no URL" }
```

Lines, in any order, until the first blank line:

| line | meaning |
| --- | --- |
| `size: <spec>` | how large the content is relative to the code (see Size). Several `size:` lines may give per-selector sizes. |
| `http://…`, `https://…` | URL of the glyph document |
| `blue://<device>/<path>` | fetch the document over Bluetooth from another QRX device (see [BLUE.md](BLUE.md)) |
| `multi` | the same code may be shown several times in the scene |
| `name: value` | any other header, kept in `GlyphBarcode.headers` |
| `name` | a flag header, stored as `name: 1` |

After the first blank line, the rest of the payload is the *body*. When the
payload has no URL, the body is parsed as the glyph document itself, which
lets a QR code work with no network at all.

A payload that is not in this format (a plain URL, plain text) is still
usable: a bare `http(s)://` line is a URL, and anything else is shown as text.

## Size

`<width>x<height>[:selector]`, or a single value used for both, or `~` for the
default (`*1x*1`, the size of the code).

```
width  := [*] number [anchor [offset]]
anchor := l | c | r      (t | c | b for height)
```

* `*` makes the value a multiple of the code's size; without it the value is in code units.
* `anchor` says which edge of the code the content is anchored to; `offset` moves it from there.
* `selector` is `:normal` (default), `:fixed`, `:focus` or `:active`; the app picks the size matching the glyph's state.

Examples: `*2` (twice the code, centred), `10l10x20b10` (10 wide anchored left
offset 10, 20 high anchored bottom offset 10), `1x2:active`.

## Glyph documents

A JSON object with one key starting with `_` that names the type. Its value
holds type options; the other keys are the content.

| type | fields |
| --- | --- |
| `_image` | `url` |
| `_avplayer` | `url`; options `loop` |
| `_web` | `url` |
| `_button` | `text`; optional `action` (a JsonUI action, e.g. `{ "name": "toast", "args": {...} }`) |
| `_ui` | the whole document is a [JsonUI](https://github.com/bclnet/JsonUI) document: `_ui` is its header (state, script, strings) and the remaining keys are the root node |

Unknown types are shown as a placeholder with the type name.

`_ui` glyphs get the host actions `toast` (shows `args.message`), `open`
(opens `args.url`), `led` (sets the Bluetooth LED, `args.r/g/b`) and
`dismiss`; the `submitSurvey`-style names in the examples are reported by the
app's default fallback handler.
