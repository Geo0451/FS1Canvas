# FS1Replay

**Design custom watch faces for the Fastrack Revoltt FS1 and send them to your watch over Bluetooth.**

FS1Replay is a reverse-engineered, fully-featured watch-face editor and BLE sender in one Android app. No PC needed — edit and upload straight from your phone.

## Features

- **Visual editor** with live preview: reposition, resize, recolour, and hide widgets (time, date, steps, heart rate, battery, etc.)
- **Photo editor** in fullscreen: crop, zoom (0.25×–10×), rotate 90°, flip, with Fill/Fit/Stretch presets and composition grid
- **18 curated fonts** (digits *and* A-Z, so they also work on weekday / AM-PM / `%`), including manga/brush styles: Bangers, Yuji Boku, Permanent Marker, Rock Salt, Pirata One, UnifrakturCook, Creepster, Rubik Wet Paint, Dela Gothic One, Shojumaru, and the originals
- **Fullscreen editor** with widget nudge pad (1/5/10 px steps), live readout, and hide/show toggle
- **Direct BLE upload** to the watch with block-by-block ack verification and progress bar
- **Bundled templates** (default-ring.bin, default-stream.bin) — original backgrounds in the app's own style, no Fastrack branding; add your own .bin files to the app folder and they show up automatically
- **Neon Orbit theme** throughout: ink background, violet accents, orange actions

## Installation

1. **Download** `app-debug.apk` from the [latest release](../../releases)
2. **On your phone:**
   - Settings → About Phone → tap "Build Number" 7 times (enables Developer Options)
   - Settings → Developer Options → enable "Unknown sources" (or "Install from unknown sources")
   - Open the APK file (usually in Downloads) and tap Install
3. **Done** — the app appears on your home screen

No Google Play account needed. The debug APK will ask "Do you trust this app?" during install; tap "Install anyway".

## How to use

1. **Pick a template** (or load a .bin from your phone)
2. **Edit**: drag widgets, change fonts and colours, adjust sizes
3. **Photo**: fullscreen editor → Photo tab to crop and zoom the background image
4. **Fullscreen**: tap ⛶ above the preview to fine-tune widget positions with arrow keys
5. **Send to watch**: tap "Send to watch", choose your watch MAC (or it remembers the last one), and wait for the progress bar

The watch MAC defaults to `B1:82:66:8C:6C:E1` (from the original fs1replay app); change it in "Watch MAC / reconnect".

Downloads (patched.bin, writes_body.txt) save to `Downloads/FS1/`.

## What's inside

- **editor.html** — the editor, ported from the original web version with native glue for Android
- **WatchLink.kt** — BLE transport: GATT connect, AT setup, block upload with per-block ack check
- **MainActivity.kt** — WebView host, JS bridge, permissions, device picker
- **default-ring.bin, default-stream.bin** — bundled templates with the widget layout of the original capture, backgrounds replaced with original art (no third-party branding)

## Technical notes

- **Protocol**: FF FF 42 47 header, 242-byte BLE chunks, AT-command setup/teardown
- **File format**: 0xF4 faces with a fixed 15-widget table, RLE-encoded glyph directory, RGB565 big-endian images
- **Reverse-engineered** from HCI captures and the vendor SDK, then validated on hardware

## Building from source

1. Open the FS1Replay folder in Android Studio (Hedgehog or newer)
2. Let Gradle sync
3. Click the **Build** hammer icon (or Build menu → Build APK)
4. Wait ~3 min for "BUILD SUCCESSFUL"
5. APK appears in `app/build/outputs/apk/debug/app-debug.apk`

To make a signed release APK for your own distribution, see [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md).

## License

Reverse-engineering work (protocol, file format, widget table) documented in README files. The app UI and BLE code are original. The original watch-face editor and bgproto.py are from the FS1 custom watch face project.

## Credits

- Fastrack Revoltt FS1 reverse-engineering by [original project](https://github.com/search?q=fs1+watchface)
- App icon: watch dissolving into 0s and 1s (Neon Orbit palette)
- Fonts from Google Fonts (Fontsource), all OFL licensed

## Issues & contributions

Found a bug? Have a font idea? Open an issue or a PR.

---

**Getting started?** Load `default-ring.bin` or `default-stream.bin`, edit the widgets, change fonts/colours, and send to watch.

## A note on the bundled templates

The two bundled `.bin` files started life as faces made in the official Fastrack app. Everything visual in them has been replaced with originals: the background art (which had the Fastrack logo baked in), the digit glyphs, the weekday / AM-PM / `%` text, and the battery icons. Text and digits are now Orbitron by default; the battery icons are drawn from scratch. The widget layout (positions, sizes, which data each widget shows) is unchanged - that is structural data from the reverse-engineered file format, not artwork.

Fonts are not locked in: every text widget (clock digits, weekday, AM/PM, the `%` sign, steps, etc.) has its own font picker in **Colours & fonts**, with 18 bundled fonts plus any font installed on your device. Icon-only sets (like the battery graphic) have no font option, since they are pictures rather than text.

Rebuilding the glyph tables is done with the same code the editor's font feature uses, and the results are checked against the original files' structure (length fields, directory, the 16-byte `UI_v2.1` trailer). That said, they have not been sent to a real watch since the last change - if a send fails or a face looks wrong, please open an issue with the block number from the log.
