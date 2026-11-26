# KitchenHub

A Nest Hub-style kitchen launcher and always-on display for an Echo Show 8 (Android 11), built with Material 3 Expressive.

## Features

- **Home / Media / Communicate / Home control** pages with bento-style cards
- **StandBy clock** with burn-in-safe pixel shifting, night mode, and unread sticky notes on the lock screen
- **Sticky notes** with voice dictation (Note, Reminder, or a custom author)
- **Timers and alarms** that read the Google Clock app and show a full-screen ringing overlay
- **Weather**, calendar agenda (iCal), Bluetooth, media controls with album-art theming
- **Wake word** ("Hey Google") using a bundled openWakeWord ONNX model, handing off to the Assistant
- **Google Home** device control through the Google Home APIs (lights, plugs, thermostats)
- **Shopping list** and **kitchen converter** utilities
- OpenGL ES shader effects (turbulence, ripple, weather) capped at 15 fps

## Requirements

- Android Studio / JDK 17, Gradle wrapper included
- `minSdk 30` / `targetSdk 30`
- For Google Home control: Google Play services 24.44 or newer on the device

## Build

```powershell
.\gradlew.bat assembleDebug -PkitchenhubAbis=x86_64     # emulator
.\gradlew.bat assembleDebug -PkitchenhubAbis=armeabi-v7a # Echo Show 8
```

## Google Home setup

The Google Home APIs are not on public Maven. To build with Google Home support:

1. Download the Home APIs Android SDK (`home.android.sdk_*.zip`) from <https://developers.home.google.com/>.
2. Extract it into a `local-maven/` folder at the project root (the folder is git-ignored).
3. In Google Cloud, create an Android OAuth client for package `com.kyle.kitchenhub` and your debug keystore SHA-1, and import the project into the Google Home Developer Console.
4. In the app, open **Settings > Google & UK Ecosystem** and set your Project ID and OAuth Client ID, then tap **Connect Google Home**.

## Project layout

```
app/src/main/java/com/kyle/kitchenhub/
  core/      audio, env, shaders, UI helpers
  data/      config, repositories (weather, notes, smart home)
  service/   wake word, media bridge, timers, mic arbitration
  ui/        launcher pages, clock, dialogs, settings
scripts/     device setup and dev helpers
memory-bank/ design tokens and progress notes
```

## Releases

**Settings > System > Check for Update** compares the installed version with the latest GitHub release of this repository.
