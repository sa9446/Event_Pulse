# EventPulse — Demo Video Script

## Overview
A 3-5 minute demo showing the key features of EventPulse running on two Android devices over BLE mesh.

**Setup Required:** 2 Android phones (API 26+) with BLE, both running EventPulse debug APK, within 10 meters of each other.

---

## Scene 1: Splash Screen & Onboarding (0:00 – 0:30)

**Visual:** Phone boots app → animated splash screen with pulsing rings

**Narration:**
> "EventPulse is an offline, serverless event engagement platform. No WiFi, no cell service, no servers — just pure BLE mesh networking between every device in the venue."

**Actions:**
1. Tap app icon
2. Show splash screen animation (2.5s pulse rings + "EventPulse" title)
3. Show onboarding → BLE permissions → location permissions
4. Land on main chat screen

**Text overlay:** *"Zero infrastructure required"*

---

## Scene 2: Mesh Discovery & Crowd Pulse (0:30 – 1:00)

**Visual:** Header bar showing peer connections and live crowd density ring

**Narration:**
> "As devices come into range, EventPulse automatically discovers them and builds a mesh network. The Crowd Pulse Ring shows venue density in real-time."

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
> "EventPulse includes in-app media capture. Photos are automatically compressed to WebP and split into chunks for BLE transmission — all without saving to your gallery."

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
> "Sent something by accident? EventPulse's Mesh Recall lets you retract messages — even media — across every connected device in the venue."

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
> "Built for speed — EventPulse sends media chunks in parallel batches, caches compressed bitmaps in a 4MB LruCache, and compiles critical paths for AOT on install. The result? Everything feels instant."

**Actions:**
1. Show rapid message sending (type 3-4 messages quickly)
2. Show camera → photo → received in < 2 seconds
3. Show app startup (< 1 second to splash)

**Text overlay:** *"Parallel mesh = 4x faster media"*

---

## Scene 8: Architecture Summary (4:00 – 4:30)

**Visual:** Architecture diagram or feature grid

**Narration:**
> "EventPulse combines battle-tested BLE mesh infrastructure from bitchat with new features for venues — structured channels, media sharing, retraction, SOS, and real-time crowd sensing. All offline, all peer-to-peer, all private."

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
> "EventPulse is open source and built for hackathons. Fork it, extend it, make it your own."

**Text overlay:** *github.com/sa9446/Event_Pulse*

---

## Production Notes

- **Background music:** Upbeat electronic or lo-fi (royalty-free)
- **Captions style:** White text with semi-transparent black bar
- **Phone display:** Record in landscape with phone in a stand
- **Screen recording:** Use Android's built-in screen recorder for clean capture
- **Audio voiceover:** Record with a good USB mic in a quiet room
- **Editing:** Use CapCut, DaVinci Resolve, or Premiere Pro

## Suggested Tools

| Tool | Purpose |
|------|---------|
| OBS Studio | Record phone screen + voiceover |
| DaVinci Resolve | Free video editing |
| Canva | Text overlays and end card |
| Unsplash | Background stock footage |
