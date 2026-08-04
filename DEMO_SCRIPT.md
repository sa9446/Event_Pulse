# Dead Air — Demo Video Script

## Overview
A 3-5 minute demo showing the key features of Dead Air running on two Android devices over BLE mesh.

**Setup Required:** 2 Android phones (API 26+) with BLE, both running Dead Air debug APK, within 10 meters of each other.

---

## Scene 1: Splash Screen & Onboarding (0:00 – 0:30)

**Visual:** Phone boots app → animated splash screen with pulsing rings

**Narration:**
> "Dead Air is an offline, serverless event engagement platform. No WiFi, no cell service, no servers — just pure BLE mesh networking between every device in the venue."

**Actions:**
1. Tap app icon
2. Show splash screen animation (2.5s pulse rings + "Dead Air" title)
3. Show onboarding → BLE permissions → location permissions
4. Land on main chat screen

**Text overlay:** *"Zero infrastructure required"*

---

## Scene 2: Mesh Discovery & Crowd Pulse (0:30 – 1:00)

**Visual:** Header bar showing peer connections and live crowd density ring

**Narration:**
> "As devices come into range, Dead Air automatically discovers them and builds a mesh network. The Crowd Pulse Ring shows venue density in real-time."

**Actions:**
1. Show device 1 scanning, device 2 nearby
2. Peer count jumps from 0 → 1 → 2+
3. Crowd Pulse Ring changes color: UNKNOWN (gray) → LOW (green) → MEDIUM (yellow)
4. Hover/tap on the ring shows "MEDIUM — 4 peers"

**Text overlay:** *"Live crowd density via RSSI"*

---

## Scene 3: Channel Chat (1:00 – 1:45)

**Visual:** Channel tabs (#announcements, #qa, #general) with messages flowing

**Narration:**
> "Messages are organized into channels. Announcements from organizers, Q&A for audience questions, and general chat for everyone."

**Actions:**
1. Switch to #announcements tab
2. Type message on device 1 → appears on device 2 instantly
3. Switch to #qa tab on device 2 → type question → appears on device 1
4. Show verified badges on messages (Ed25519 signed identities)
5. Show message cards with sender name, timestamp, channel tag

**Text overlay:** *"Structured chat over BLE mesh"*

---

## Scene 4: Media Sharing (1:45 – 2:30)

**Visual:** In-app camera capture + voice note recording buttons

**Narration:**
> "Dead Air includes in-app media capture. Photos are automatically compressed to WebP and split into chunks for BLE transmission — all without saving to your gallery."

**Actions:**
1. Tap camera button → system camera opens
2. Take photo → instant compression + mesh broadcast
3. On receiving device: image thumbnail appears in chat
4. Tap microphone icon → hold-to-record voice note
5. Release → voice note appears on both devices with waveform player

**Text overlay:** *"Compressed media over BLE"*

---

## Scene 5: Mesh Recall (2:30 – 3:00)

**Visual:** Long-press context menu → "Retract for Everyone"

**Narration:**
> "Sent something by accident? Dead Air's Mesh Recall lets you retract messages — even media — across every connected device in the venue."

**Actions:**
1. Long-press a sent message
2. Select "Retract for Everyone"
3. RETRACT packet broadcasts across mesh
4. On receiving device: message disappears, shows "This message was retracted by sender"
5. Associated media files deleted from local storage

**Text overlay:** *"Mesh-wide retraction"*

---

## Scene 6: Emergency SOS (3:00 – 3:30)

**Visual:** Red SOS button → pulsing alert banner on all devices

**Narration:**
> "In an emergency, the SOS button broadcasts a priority alert that bypasses all filters and shows a pulsing red banner on every connected device."

**Actions:**
1. Tap SOS button on device 1
2. Device 2 shows red pulsing banner: "🚨 EMERGENCY from [Sender]"
3. Dismiss the alert with the X button
4. SOS banners have a 60-second dedup window to prevent spam

**Text overlay:** *"Priority emergency broadcast"*

---

## Scene 7: Performance (3:30 – 4:00)

**Visual:** Split screen or side-by-side showing fast operations

**Narration:**
> "Built for speed — Dead Air sends media chunks in parallel batches, caches compressed bitmaps in a 4MB LruCache, and compiles critical paths for AOT on install. The result? Everything feels instant."

**Actions:**
1. Show rapid message sending (type 3-4 messages quickly)
2. Show camera → photo → received in < 2 seconds
3. Show app startup (< 1 second to splash)

**Text overlay:** *"Parallel mesh = 4x faster media"*

---

## Scene 8: Architecture Summary (4:00 – 4:30)

**Visual:** Architecture diagram or feature grid

**Narration:**
> "Dead Air combines battle-tested BLE mesh infrastructure with new features for venues — structured channels, media sharing, retraction, SOS, and real-time crowd sensing. All offline, all peer-to-peer, all private."

**Text overlay cards:**
- 🏟️ Venue Crowd Pulse
- 💬 Channel Chat
- 📸 Media Sharing
- 🔙 Mesh Recall
- 🚨 Emergency SOS
- 🔒 End-to-End Encrypted

---

## Scene 9: Open Source (4:30 – 5:00)

**Visual:** GitHub repo QR code or URL

**Narration:**
> "Dead Air is open source and built for hackathons. Fork it, extend it, make it your own."

**Text overlay:** *github.com/sa9446/Event_Pulse*

---

## Production Notes

- **Background music:** Upbeat electronic or lo-fi (royalty-free)
- **Captions style:** White text with semi-transparent black bar
- **Phone display:** Record in landscape with phone in a stand
- **Screen recording:** Use Android's built-in screen recorder for clean capture
- **Audio voiceover:** Record with a good USB mic in a quiet room
- **Editing:** Use CapCut, DaVinci Resolve, or Premiere Pro

## Storyboard — Shot-by-Shot (recordable)

Camera conventions: **A** = wide/setup shot (both phones on stands), **B** = close-up of
phone 1 screen, **C** = close-up of phone 2 screen, **D** = screen recording overlay
(cropped fullscreen). Record every shot 3–5 seconds longer than the listed cut.

| Shot | Scene | Timecode | Camera | On-screen action | Narration (exact line) | Text overlay |
|------|-------|----------|--------|------------------|------------------------|--------------|
| 1 | Splash | 0:00–0:06 | D | Tap app icon → splash pulse rings → "Dead Air" title | "Dead Air is an offline, serverless event engagement platform." | *Zero infrastructure required* |
| 2 | Onboarding | 0:06–0:18 | D | Permission dialogs: BLE → Nearby devices → Location → Allow all | "No WiFi, no cell service, no servers — just pure BLE mesh networking." | — |
| 3 | Landing | 0:18–0:30 | D | Land on main chat screen; header bar in frame | "Between every device in the venue." | — |
| 4 | Discovery | 0:30–0:42 | A | Both phones visible; bring phone 2 closer | "As devices come into range, Dead Air automatically discovers them and builds a mesh network." | *Live crowd density via RSSI* |
| 5 | Pulse | 0:42–1:00 | B | Peer count 0 → 1 → 2; ring UNKNOWN → LOW → MEDIUM | "The Crowd Pulse Ring shows venue density in real time." | *MEDIUM — 4 peers* |
| 6 | Channels | 1:00–1:15 | B | Switch `#announcements`; type on phone 1 | "Messages are organized into channels. Announcements from organizers…" | *Structured chat over BLE mesh* |
| 7 | Cross-device | 1:15–1:30 | C | Phone 2 shows the message instantly; verified badge visible | "…Q&A for audience questions, and general chat for everyone." | — |
| 8 | Round trip | 1:30–1:45 | B + C | Phone 2 → `#qa` → question → appears on phone 1 | "Each message carries a sender name, timestamp, channel tag, and verified badge." | — |
| 9 | Camera | 1:45–2:05 | D | Tap camera button → take photo → auto-send | "Photos are automatically compressed to WebP and split into chunks for BLE transmission." | *Compressed media over BLE* |
| 10 | Media received | 2:05–2:15 | C | Thumbnail appears on phone 2 (watch it load) | "…all without saving to your gallery." | — |
| 11 | Voice note | 2:15–2:30 | D | Hold mic → record → release → waveform on both | "Voice notes record at low bitrate and stream as a waveform." | — |
| 12 | Mesh Recall | 2:30–2:50 | B | Long-press own message → Retract for Everyone | "Sent something by accident? Dead Air's Mesh Recall lets you retract messages — even media." | *Mesh-wide retraction* |
| 13 | Retract lands | 2:50–3:00 | C | Message disappears on phone 2 + retracted badge | "…across every connected device in the venue." | *This message was retracted by sender* |
| 14 | SOS | 3:00–3:15 | B | Tap red SOS button | "In an emergency, the SOS button broadcasts a priority alert that bypasses all filters." | *Priority emergency broadcast* |
| 15 | SOS lands | 3:15–3:30 | C | Pulsing red banner on phone 2 → dismiss | "…showing a pulsing red banner on every connected device. SOS has a 60-second dedup window." | *🚨 EMERGENCY from Alice* |
| 16 | Speed | 3:30–3:50 | B | Rapid-fire 3–4 messages; then camera → photo → arrives | "Built for speed — parallel chunked transfer, bitmap caching, and AOT compilation." | *Parallel mesh = 4x faster media* |
| 17 | Architecture | 3:50–4:15 | A | Split screen or feature-grid card | "Channels, media, retraction, SOS, crowd sensing — all offline, all peer-to-peer." | 6 feature chips |
| 18 | Open source | 4:15–4:30 | A | GitHub repo QR / URL on end card | "Dead Air is open source and built for hackathons. Fork it, make it your own." | *github.com/sa9446/Event_Pulse* |
| 19 | Outro | 4:30–4:40 | A | Logo, tagline, socials | "Dead Air — the venue that runs on nothing but the crowd." | *Dead Air* |

### Filming order (fastest retake loop)

1. **Shots 1–3, 6, 9, 11, 12, 14, 16** (phone 1 close-ups, screen recording) — do these first;
   no second phone needed for the visual.
2. **Shots 7–8, 10, 13, 15** (phone 2 reactions) — needs both phones live; capture right after
   each send so the receiving screen is fresh.
3. **Shots 4–5** (two-phone wide) — last, when both screens show full history.
4. **Shots 17–19** — graphics-only, edit in post; no devices needed.

### Retake notes

- BLE timing varies — never narrate over a live send; record narration **separately** and
  cut to the action clip.
- If a peer-count jump is slow, add 5 s of B-roll of the venue/crowd to cover the gap.
- Re-record a scene if any permission dialog, notification, or stray toast appears on screen.
- Keep the status bar visible (battery, time) so reviewers can't claim screen recording was used.

### Post-production checklist

- [ ] Captions: white text, semi-transparent black bar, 60 px
- [ ] Royalty-free lo-fi/electronic bed at ~20% volume under narration
- [ ] Cut narration gaps to < 0.5 s; punch in with 10% zoom on key UI moments
- [ ] Export 1080p60 H.264; final length 4:00–5:00

## Suggested Tools

| Tool | Purpose |
|------|---------|
| OBS Studio | Record phone screen + voiceover |
| DaVinci Resolve | Free video editing |
| Canva | Text overlays and end card |
| Unsplash | Background stock footage |
