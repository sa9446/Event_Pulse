# Dead Air — Two-Device Demo Runbook

Practical, step-by-step runbook for demoing Dead Air on two Android phones
over BLE mesh. Pair this with [DEMO_SCRIPT.md](DEMO_SCRIPT.md) for the filming
storyboard.

---

## 1. Prerequisites

- **2 Android phones**, API 26+ (Android 8+), Bluetooth 4.0+ (BLE). Real devices
  are **required** — the BLE mesh doesn't work on emulators.
- **USB cables** for both phones (for `adb install`).
- Phones within **~10 meters** of each other for the whole demo.
- Windows PC with the [signed release APK](docs/play-signing.md) ready
  (`app-universal-release.apk` from `app/build/outputs/apk/release/`).
- `adb` (Android SDK Platform Tools) on your PATH:
  `C:\Users\Saman\AppData\Local\Android\Sdk\platform-tools\adb.exe`

### Quick APK check

```bash
# From the repo root:
adb version
ls -la app/build/outputs/apk/release/app-universal-release.apk
# Verify it is signed (buildTools pinned to 37.0.0 in gradle/libs.versions.toml):
"C:\Users\Saman\AppData\Local\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-universal-release.apk
```

## 2. Install on both devices

```bash
adb devices                          # both phones must appear (enable USB debugging)
adb -s <deviceA-serial> install -r app/build/outputs/apk/release/app-universal-release.apk
adb -s <deviceB-serial> install -r app/build/outputs/apk/release/app-universal-release.apk
```

> The universal APK installs on any device. The split APKs
> (`app-arm64-v8a-release.apk` etc.) are smaller but must match the device ABI.

### First-run setup (do this BEFORE the demo, on both phones)

1. Open Dead Air → grant **Bluetooth**, **Nearby devices** (Android 12+), and
   **Location** permissions.
2. Set a distinct nickname on each device (e.g. **"Alice"** and **"Bob"**) so
   message senders are identifiable.
3. **Disable Wi-Fi and mobile data** (or use Airplane mode + re-enable Bluetooth)
   to prove the app is fully offline. BLE still works in Airplane mode.
4. Keep both screens awake (Settings → Developer options → *Stay awake*).

## 3. Demo flow (2 devices, ~4 minutes)

| # | Step | Device | What you should see |
|---|------|--------|---------------------|
| 1 | Open the app on both | A + B | Splash → chat screen; peer count **0** |
| 2 | Bring devices within range | A + B | Peer count **1 → 2**; Crowd Pulse Ring turns green/yellow |
| 3 | Alice sends `#announcements` msg | A | Message appears instantly on **both** screens with Alice's verified badge |
| 4 | Bob switches to `#qa` and replies | B | Only visible on the `#qa` tab on both devices (channel routing) |
| 5 | Camera → take photo | A | WebP-compressed thumbnail appears on B in a few seconds (chunked BLE transfer) |
| 6 | Hold-to-record voice note | B | Voice note with waveform appears on A; tap to play |
| 7 | Long-press Alice's own message → **Retract for Everyone** | A | Message disappears on BOTH devices; "retracted by sender" badge; media purged |
| 8 | Tap **SOS** | A | Red pulsing alert banner on BOTH devices (bypasses channels) |
| 9 | Dismiss banner | B | Banner clears; SOS has a 60s dedup window |

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Peer count stays 0 | Location/BLE permission missing, or phones too far apart | Grant Nearby devices + Location; move within 5–10 m |
| Messages don't cross | One phone's BLE is off, or background scan throttled | Keep both apps **in the foreground**; toggle Bluetooth off/on |
| Photo arrives slowly / never | BLE MTU + chunking is slow for large files | Stay within 5 m; keep phones still; retry once |
| SOS banner doesn't show | Rate-limited (60s dedup) | Wait 60s, or use a different device |
| App shows "untrusted publisher" on update | Cert pin mismatch | Ensure release APK signed with the key pinned in `gradle.properties` (CI keystore for CI-published builds) |
| Mic/camera button greyed out | Permission denied | Grant Camera + Microphone in app settings; re-launch app |

## 5. Demo-day checklist

- [ ] Both phones charged (>80%) and screens stay-awake enabled
- [ ] APKs installed and first-run setup done on both
- [ ] Airplane mode ON (Bluetooth re-enabled) — prove "zero infrastructure"
- [ ] Distinct nicknames set
- [ ] One pre-staged photo + one short voice note captured for instant replays
- [ ] Backup: second pair of phones if a device dies mid-demo
- [ ] Presentation remote / clicker for advancing DEMO_SCRIPT.md scenes
