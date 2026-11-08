# Shield Browser

A small, battery-friendly Android browser built on the system WebView.

## Features
- **Tracker blocking**: domain blocklist (`app/src/main/assets/blocklist.txt`), counter on the shield button. Replace the file with a bigger list (domain or hosts format) any time.
- **Canvas fingerprint shield**: `assets/canvas_shield.js` adds tiny per-site, per-session noise to canvas reads.
- **HTTPS only**: `http://` links are upgraded; mixed content and cleartext are blocked.
- **Third-party cookies off**, file/content access off, geolocation off, odd URL schemes (intent:, file:) blocked.
- **Tabs**: swipe left from the right edge, or along the bottom bar, to slide in a vertical tab rail. Tap a tile to switch, × to close, + for a new tab. Swipe right (or tap outside) to close it. The counter button on the bottom bar also toggles it. Up to 10 tabs; background tabs are paused.
- **VPN (WireGuard)**: bring your own WireGuard config (from your own server or a provider such as Mullvad or Proton). ⋮ menu > *VPN: import WireGuard config* (pick a `.conf` file or paste the text), then *VPN: connect*. A 🔒 shows on the shield button while connected. Make sure the config has a `DNS =` line so lookups go through the tunnel.
- **Low battery**: page timers paused whenever the app is in the background; no background services of its own; Lite mode (images off) toggles manually or turns on by itself in Android Battery Saver.
- **Custom colors**: 4 presets plus custom background/accent hex (⋮ menu > Theme).
- **Clear cookies & site data** from the ⋮ menu.
- Can be set as a default browser (handles http/https links).
- Launcher icon: the pixel-art image, built as an adaptive vector icon (`res/drawable/ic_launcher_foreground.xml`).

## Get the APK
**GitHub Actions (no Android setup):** every push to `main` builds the APK automatically. Open the repo's **Actions** tab, open the latest **Build APK** run, and download the `ShieldBrowser-debug-apk` artifact. Unzip it and install `app-debug.apk`. You can also run the workflow manually from the Actions tab.

**Build locally:** install Android Studio (or JDK 17 + the Android SDK), then run:

```sh
./gradlew assembleDebug
```

The Gradle wrapper downloads the pinned Gradle 8.9 for you, so no separate Gradle install is needed. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

**Install:** allow "Install unknown apps" for your file manager/browser, then open the APK. Or `adb install app-debug.apk`.

## Project layout
```
ShieldBrowser/
├── app/src/main/java/com/shieldbrowser/app/
│   ├── MainActivity.kt     # UI, tabs, WebView setup, theme
│   ├── TrackerBlocker.kt   # domain blocklist matching
│   └── VpnController.kt    # WireGuard import/connect wrapper
├── app/src/main/assets/
│   ├── blocklist.txt       # tracker/ad domains (swap in a bigger list any time)
│   └── canvas_shield.js    # per-site, per-session canvas noise
├── .github/workflows/
│   └── build-apk.yml       # CI: builds the debug APK on every push
├── gradlew                 # Gradle wrapper (pinned to Gradle 8.9)
└── README.md
```

## Notes
- The VPN covers the whole phone while connected (WireGuard full tunnel), not just this browser.
- WebView can't set its own DNS. With the VPN off, use Android's Settings > Network > Private DNS for DNS-over-TLS.
- Swipe from the right edge can clash with the system back gesture outside the middle of the screen; the bottom-bar swipe always works.

## Where to tweak
- Colors/presets: `PRESETS` in `MainActivity.kt`
- Tab limit: `MAX_TABS` in `MainActivity.kt`
- Noise strength: `perturb()` in `canvas_shield.js`
- Blocklist: `assets/blocklist.txt`
- VPN wrapper: `VpnController.kt`
