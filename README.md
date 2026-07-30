# EventPulse 🎯

**Offline, serverless event engagement & crowd dynamics platform** — powered by BLE mesh networking.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0+-purple.svg)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-26%2B-orange.svg)](https://developer.android.com)

---

## 📋 Overview

EventPulse transforms any venue into a connected, offline-first experience using Bluetooth Low Energy (BLE) mesh networking. No WiFi, no cellular data, no servers needed. Every device becomes both a node and a relay, creating a resilient ad-hoc communication network.

### Built for Hackathons 🏆

This was built from the ground up as a **judge-ready hackathon submission**, showcasing:

- **Mesh Networking:** BLE peer-to-peer mesh with automatic relay
- **Real-time Chat:** Channel-based messaging (#announcements, #qa, #general)
- **Crowd Pulse:** Live RSSI-based venue density indicator
- **Media Sharing:** In-app camera capture & voice notes over mesh
- **Mesh Recall:** Retract messages/media across all connected devices
- **Emergency SOS:** Priority broadcast that bypasses all filters
- **End-to-End Encryption:** Noise Protocol + Ed25519 signatures

---

## ✨ Features

### 🏟️ Venue Engagement
- **Crowd Pulse Ring** — Real-time density indicator (LOW / MEDIUM / PACKED)
- **Channel Tabs** — `#announcements`, `#qa`, `#general` routing
- **Verified Badges** — Ed25519-signed identity verification
- **Live Peer Count** — Active BLE devices in range

### 📱 Media & Sharing
- **In-App Camera** — Capture & compress to WebP, instantly broadcast
- **Voice Notes** — Hold-to-record AAC, auto-chunked for mesh
- **Mesh Recall** — Long-press to retract any sent message/media
- **Parallel Chunking** — 480-byte chunks sent concurrently (4x faster)

### 🔒 Security & Privacy
- **Mesh Retraction** — RETRACT packets cascade across all peers
- **Rate Limiting** — 280-char cap, 1 msg/2s throttle, duplicate detection
- **Thread-Safe Core** — All concurrent state uses `ConcurrentHashMap`
- **R8 Optimized** — Aggressive optimization + baseline profile for AOT

### 🚀 Performance
- **Parallel Mesh Send** — Chunks broadcast in batches of 4
- **Bitmap LruCache** — 4MB cache avoids re-decoding images
- **Zero-Overhead Animations** — `InfiniteTransition` paused when idle
- **Deferred Init** — 2-phase startup: critical sync → non-critical async
- **Baseline Profile** — 80+ HSPL rules for AOT compilation on install

---

## 🏗️ Architecture

```
com.eventpulse.mesh/           # New EventPulse features
├── EventPulsePacket.kt        # JSON payload model (512B max)
├── CrowdDensityCalculator.kt  # RSSI-based crowd density
├── EventPulseRateLimiter.kt   # 280-char + rate limiting
├── EventPulseSOSHandler.kt    # Emergency alert management
├── EventPulseRetractionManager.kt  # Mesh Recall engine
├── EventPulseMediaChunker.kt  # 480B chunking + WebP compression
├── EventPulseMediaCapture.kt  # Camera + voice capture UI
├── EventPulseUIComponents.kt  # Compose UI: pulse ring, tabs, cards
└── EventPulseSplashScreen.kt  # Animated branded splash

com.bitchat.android/           # Core mesh infrastructure (inherited)
├── mesh/                      # BLE mesh services & protocols
│   ├── BluetoothMeshService   # BLE coordinator
│   ├── MessageHandler         # Packet type routing
│   ├── FragmentManager        # Message fragmentation
│   ├── PacketRelayManager     # TTL-based relaying
│   └── ...
├── protocol/                  # Binary packet format
│   ├── BinaryProtocol         # Wire format (iOS-compatible)
│   ├── CompressionUtil        # Zlib deflate
│   └── BitchatPacket          # Packet data model
├── noise/                     # Noise Protocol encryption
├── crypto/                    # Ed25519 signing
├── nostr/                     # Nostr relay integration
└── ui/                        # Compose UI screens
    ├── ChatScreen.kt          # Main chat view
    ├── ChatViewModel.kt       # State management
    ├── ChatState.kt           # Centralized state
    └── MessageComponents.kt   # LazyColumn message list
```

### Data Flow

```
[BLE Scan/Advertise] → [BluetoothMeshService]
     ↓
[PacketProcessor] → [MessageHandler]
     ↓
[EventPulsePayload.parse] → [Channel Router]
     ↓
[ChatViewModel] → [ChatState] → [ChatScreen UI]
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1+) |
| JDK | 17+ |
| Android SDK | API 34 (compileSdk) |
| Gradle | 9.6.1 (wrapper) |
| Device | Physical Android 8+ (BLE required) |

### Setup

```bash
# Clone the repo
git clone https://github.com/sa9446/Event_Pulse.git
cd Event_Pulse

# Open in Android Studio
# File → Open → select Event_Pulse directory

# Let Gradle sync, then run on device:
# Run → Run 'app' (select physical device via USB)
```

### Configuration

Create `local.properties` in the project root (or Android Studio will auto-generate it):

```properties
sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk
```

The `gradle.properties` already includes JDK 21 configuration:

```properties
org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot
```

### Building from CLI

```bash
# Debug build
./gradlew assembleDebug

# Release build (with R8 optimization)
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest
```

---

## 🧪 Testing

Unit tests for EventPulse features are in `app/src/test/java/com/eventpulse/mesh/`:

| Test | File |
|------|------|
| Crowd Density Calculator | `CrowdDensityCalculatorTest.kt` |
| JSON Packet Model | `EventPulsePacketTest.kt` |
| Rate Limiter | `EventPulseRateLimiterTest.kt` |

Run with:
```bash
./gradlew testDebugUnitTest
```

---

## 🔧 R8 / ProGuard

Release builds use aggressive R8 optimization:

```properties
-mergeinterfacesaggressively
-allowaccessmodification
-repackageclasses 'ep'
-flattenpackagehierarchy 'ep'
-assumenosideeffects class android.util.Log { ... }
```

Keep rules ensure reflection-based libraries (Gson, Room, Noise Protocol, Tor/Arti) work correctly. See `app/proguard-rules.pro` for details.

---

## 📊 Baseline Profile

The app includes a baseline profile at `app/src/main/baseline-prof.txt` with 80+ HSPL rules covering:

- Application & Activity entry points
- Mesh service stack (20+ classes)
- Protocol & crypto layers
- EventPulse feature classes
- Compose hot paths (LazyColumn, Surface, etc.)
- Coroutines & Lifecycle

This ensures **AOT compilation on install** — no JIT warmup on first launch.

---

## 📜 License

This project is a fork/modification of [bitchat-android](https://github.com/permissionlesstech/bitchat-android) by Permissionless Tech. All original code retains its license.

New EventPulse additions are provided under the MIT License.

---

## 🙏 Acknowledgments

- [bitchat-android](https://github.com/permissionlesstech/bitchat-android) — The incredible BLE mesh foundation
- [Noise Protocol Framework](https://noiseprotocol.org/) — Encryption
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI
- [Material Design 3](https://m3.material.io/) — Design system
