# FS1Replay

Watch-face editor + BLE sender for the Fastrack Revoltt FS1, in one Android app.

Open this folder in Android Studio (Hedgehog or newer), let Gradle sync, run on a phone.

- `app/src/main/assets/editor.html` — your editor, ported as-is (+ native glue at the bottom of the script).
- `app/src/main/assets/templates/*.bin` — bundled templates (goof.bin, clean2.bin). **Drop any new
  0xF4 `.bin` in this folder and rebuild — it shows up in the Template dropdown automatically.**
  Also: load a .bin from the phone in the editor, tap "Keep this template in the app", and it is saved
  to the app's private storage and listed as "(yours)" after a restart — no rebuild needed.
- `WatchLink.kt` — BLE: connect, AT setup, block upload with ack check, closing AT+CCS:0.
- `MainActivity.kt` — WebView host, JS bridge (`Android.*`), permissions, device picker.

Flow: pick template -> edit -> **Send to watch**. The watch MAC defaults to B1:82:66:8C:6C:E1 (from your replay app); change it with "Choose / reconnect watch". Downloads (patched.bin, writes_body.txt) save to Downloads/FS1/.

## UI notes
- Fonts: 18 curated (8 original + 10 artistic: Bangers, Yuji Boku, Permanent Marker, Rock Salt, Pirata One,
  UnifrakturCook, Creepster, Rubik Wet Paint, Dela Gothic One, Shojumaru), digit-subset and embedded (all OFL).
  Pick them from the swipeable gallery in each widget's colour/font card.
- Fullscreen editor: the ⛶ button above the preview. Tap/drag widgets, or pick one from the chip row and nudge
  with the pad (centre button cycles 1/5/10 px, hold arrows to repeat). Back button closes it.
- Photo crop/zoom: Fullscreen editor -> **Photo** tab. One finger drags, two fingers pinch-zoom (0.25x-10x), plus
  slider, rotate 90°, flip, Fill/Fit/Stretch presets and a thirds grid. Preview and exported file use the same transform.
- App icon / logo: one mark - the watch dissolving into 0s and 1s (adaptive icon; Neon Orbit palette: ink #0D0B14, violet #8E73F0, orange #F28A3C) in res/mipmap-*; includes a monochrome layer for Android 13 themed icons.
- Theme: Neon Orbit everywhere. CSS variables live at the top of editor.html (--bg, --panel, --line, --amber = violet accent); orange is the primary-action colour.
