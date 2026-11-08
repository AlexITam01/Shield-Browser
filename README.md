# Shield Browser

A small, battery-friendly Android browser built on the system WebView.

## Features
- **Tracker blocking**: domain blocklist (`app/src/main/assets/blocklist.txt`), counter on the shield button. Replace the file with a bigger list (domain or hosts format) any time.
- **Canvas fingerprint shield**: `assets/canvas_shield.js` adds tiny per-site, per-session noise to canvas reads.
- **HTTPS only**: `http://` links are upgraded; mixed content and cleartext are blocked.
- **Third-party cookies off**, file/content access off, geolocation off, odd URL schemes (intent:, file:) blocked.
- **Low battery**: page timers paused whenever the app is in the background; no background services; Lite mode (images off) toggles manually or turns on by itself in Android Battery Saver.
- **Custom colors**: 4 presets plus custom background/accent hex (the palette button).
- Can be set as a default browser (handles http/https links).

## Get the APK
**Easiest (GitHub Actions, no Android setup):**
1. Push this folder to a GitHub repo.
2. Open the repo's **Actions** tab, run **Build APK** (it also runs on every push).
3. Download the `ShieldBrowser-debug-apk` artifact, unzip it, and install `app-debug.apk`.

**Locally:** install Android Studio (or JDK 17 + Android SDK + Gradle 8.9) and run `gradle assembleDebug`.
The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

**Install:** allow "Install unknown apps" for your file manager/browser, then open the APK. Or `adb install app-debug.apk`.

## Not included yet
- VPN (a local DNS-filter `VpnService` or a WireGuard client would be the next module).
- Encrypted DNS: WebView can't set its own DNS, so use Android's Settings > Network > Private DNS for DNS-over-TLS.

## Where to tweak
- Colors/presets: `PRESETS` in `MainActivity.kt`
- Noise strength: `perturb()` in `canvas_shield.js`
- Blocklist: `assets/blocklist.txt`
