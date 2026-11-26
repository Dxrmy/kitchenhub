# KitchenHub Design Tokens & Asset Specs

## Target Hardware & Parity
- **Physical:** Echo Show 8 1st Gen (`crown`), LineageOS 18.1 (API 30), `armeabi-v7a`, 1280x800 @ 189 dpi, 2GB RAM.
- **Desktop AVD:** `EchoShow8_1280x800`, API 30 (`google_apis;x86_64`), 1280x800, 189 dpi, 2048MB RAM, `hw.audioInput=yes`, `hw.audioOutput=yes`.

## Android 17 Material 3 Expressive Tokens
- **Springs (`androidx.dynamicanimation`):**
  - Spatial / Tile Press: `scale = 0.94f`, `DAMPING_RATIO_MEDIUM_BOUNCY (0.5f)`, `STIFFNESS_MEDIUM (1500f)`.
  - Slider / Edge Snap: `DAMPING_RATIO_LOW_BOUNCY (0.75f)`, `STIFFNESS_MEDIUM_LOW (200f)`.
  - Effects (color/alpha): `220ms`–`300ms`, `FastOutSlowInInterpolator` (no overshoot).
- **Frosted Glass (`FrostedBackdropEngine`):**
  - Cached downscaled backdrop: `160x100` (`ARGB_8888`, ~64 KB), single-pass stack blur (`radius = 18`) on backdrop change only.
  - Translucent surface scrim: `#A61C1E26` (dark tonal) / `#A6` + dynamic `Palette` dark-muted/vibrant tint.
  - Top-edge specular highlight: `1dp` stroke `#38FFFFFF`; subtle dither noise alpha `0x0A`.
  - Corner radii: Cards/Panels `28dp`, Sliders `24dp`, Pills/Chips `999dp`.
- **Pixel Sparkle (`PixelSparkleView`):**
  - Pool: `32` dots in primitive `FloatArray` (zero allocations in `onDraw`), `450ms` outward wave, white `#FFFFFFFF` + dynamic accent tint, radii `2.5dp`–`5dp`.

## iOS StandBy Clock Specs
- **Float:** `Fredoka` bold (`210sp`), layered dual-tone palette (`#FF9F0A` / `#FF375F`, `#30D158` / `#0A84FF`, `#BF5AF2` / `#5E5CE6`), date/alarm tucked at lower numeral contour.
- **Solar:** `PlusJakartaSans` semibold (`176sp`), luminous horizon gradient (Dawn `#FF7E5F->#FEB47B->#1A1A2E`, Day `#00B4DB->#0083B0->#0F2027`, Dusk `#F857A6->#FF5858->#1F1C2C`, Night `#141E30->#243B55`).
- **Digital:** `PlusJakartaSans` extrabold (`200sp`), edge-to-edge rounded numerals, top/bottom date & weather pills.
- **Analog:** Circular dial (`320dp`), 60 tick marks (`12` major, `48` minor), contrasting hour/minute hands, `60fps` sweep second hand (`1Hz` in Night Mode), circular date complication.
- **Minimal Mono:** High-contrast `#FFFFFFFF` on `#00000000`, `190sp` crisp tabular numerals.
- **Night Mode (22:00–07:00):** Monochrome red `#FF2D20` (primary) / `#C91D14` (secondary) on `#000000` pure black.
- **Pixel Shift:** Smooth `±6px` micro-orbit translation every `60s`.

## Asset URLs (Verified HTTP 200)
- **ORT:** `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` (`armeabi-v7a`, `x86_64`)
- **ONNX Models (`openWakeWord v0.5.1`):**
  - `melspectrogram.onnx`: `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx`
  - `embedding_model.onnx`: `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx`
  - `hey_jarvis_v0.1.onnx`: `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.onnx`
- **Fonts:**
  - `Caveat.ttf`: `https://raw.githubusercontent.com/google/fonts/main/ofl/caveat/Caveat%5Bwght%5D.ttf`
  - `PatrickHand-Regular.ttf`: `https://raw.githubusercontent.com/google/fonts/main/ofl/patrickhand/PatrickHand-Regular.ttf`
  - `Fredoka.ttf`: `https://raw.githubusercontent.com/google/fonts/main/ofl/fredoka/Fredoka%5Bwdth%2Cwght%5D.ttf`
  - `PlusJakartaSans.ttf`: `https://raw.githubusercontent.com/google/fonts/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf`

## M4 Launcher Architecture Override (Google Nest Hub + Android 17 Expressive)
- **Horizontal `ViewPager2` (4 Calm Pages, Top Pill Tab Strip, `28dp` Cards, >= `64dp` Hit Targets):**
  - **Tab 1 — Time-of-Day Home:** Dynamic greeting + Open-Meteo weather card, Active Multi-Timers card, Pinned Handwritten Sticky Note card, 7-Day iCal preview card.
  - **Tab 2 — Media:** Hero frosted Now Playing card (live `Palette` album-art tint), YouTube Music, SmartTube, Bluetooth A2DP Speaker Pair card.
  - **Tab 3 — Communicate & Notes:** One-tap Voice Sticky Note card, Google Meet quick-dial tiles, WhatsApp companion tile, Google Keep shopping list card.
  - **Tab 4 — Home & Macros:** Cooking Mode, Bedtime Mode, Quiet/DND, Enter StandBy Clock, optional RTSP PiP.
- **Swipe-Down Frosted Quick Settings Shade:** Music Volume, Alarm/Voice Volume, Brightness sliders + Quiet DND, Bluetooth Pair, Sleep Screen, Settings toggles.
- **Swipe-Up Frosted App Tray:** Translucent drawer for YouTube Music, Keep, Meet, WhatsApp, SmartTube, Settings.
- **Idle Transition:** 60s inactivity fades into iOS StandBy clocks; double-tap returns to Tab 1 with spring + `PixelSparkleView` burst.
