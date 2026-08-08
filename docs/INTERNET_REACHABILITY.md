# Internet Reachability: Relay + Onion Delivery Beyond Wi-Fi Range

**Status:** Design
**Scope:** Deliver private messages to any user with an internet connection — no physical proximity, no shared Wi-Fi — with offline queueing, while preserving the app's post-quantum security stance and the mesh as a bonus transport.
**Related:** `docs/sync.md`, `docs/SOURCE_ROUTING.md`, `docs/NOISE_PEER_ID_BINDING.md`, `docs/GeohashPresenceSpec.md`

---

## 1. Goal

The app must behave like a normal messenger for users who are not in BLE/Wi-Fi range:

1. **Any-to-any delivery** over the internet (WhatsApp-style reachability), not just to mutual favorites met on mesh.
2. **Offline queueing** — messages sent while the recipient is offline are delivered when they reconnect (already exists; must be extended to first-contact).
3. **Post-quantum confidentiality on the internet path**, matching the mesh's `Noise_XXhfs_25519+MLKEM768` guarantee.
4. **Anonymity option** — Tor routing for relay traffic (already built; needs to cover the relay transport explicitly).
5. **Mesh stays additive** — same conversation, whichever path delivered the message; receipts unify.

## 2. Current state (mostly built — the foundation exists)

This design is a *completion*, not a greenfield build. The app already ships an internet layer:

| Component | File | What it does |
|---|---|---|
| Relay client | `nostr/NostrRelayManager.kt` | WebSocket pools to public Nostr relays (damus, primal, + `assets/nostr_relays.csv`, geohash-pinned relays), reconnect with a bounded pending-event queue, event dedup. |
| Private DMs | `nostr/NostrProtocol.kt` | **NIP-17** private messages: rumor (kind 14) → seal (kind 13, sender-signed) → gift-wrap (kind 1059). Relays see ciphertext only. |
| Offline transport | `nostr/NostrTransport.kt` | Sends PMs, delivery acks, read receipts, favorite notifications over relays; throttled read-ack queue. |
| Routing + outbox | `services/MessageRouter.kt` | Mesh-first → Nostr → **scheduled outbox** (TTL expiry, per-peer bounds, handshake kick with backoff, flush on session/peer/npub events). This is the offline-queueing engine. |
| Background subs | `nostr/NostrBackgroundRuntime.kt`, `NostrSubscriptionManager.kt` | Process-owned subscriptions (account DMs via gift wraps, geohash chat/presence) that survive without an Activity. |
| Tor | `net/ArtiTorManager.kt`, `net/OkHttpProvider.kt` | Arti SOCKS proxy; all OkHttp (including WebSockets) routes through Tor when ON (default ON). |
| Identity bridge | `identity/SecureIdentityStateManager.kt`, `favorites/FavoritesPersistenceService.kt`, `services/ContactDirectory.kt` | Noise static key ↔ Nostr npub mapping, **indexed only after a Noise handshake proves key possession** (see `docs/NOISE_PEER_ID_BINDING.md`). |
| Presence (geo) | kind 20001 geohash presence | Per-location presence heartbeats; account-level presence does not exist yet. |

### 2.1 What already works end-to-end

- Two users who have **met on mesh, become mutual favorites**, and have each other's npub can already exchange DMs, delivery acks, and read receipts over the internet with offline queueing, via `MessageRouter.sendPrivate` → `canSendViaNostr` → `NostrTransport.sendPrivateMessage`.

### 2.2 The gaps that block WhatsApp-style reachability

1. **G1 — First-contact delivery is blocked.** `MessageRouter.canSendViaNostr` requires `isMutualFavorite && peerNostrPublicKey != null`. A stranger who has never met you on mesh **cannot** reach you over the internet; the message just queues and expires (`OUTBOX_MESSAGE_TTL_MS`). There is no "add by npub / share contact card / invite link" flow.
2. **G2 — Sending requires a locally-known mesh peerID for the recipient.** `NostrTransport.sendPrivateMessage` bails unless `FavoritesPersistenceService.findPeerIDForNostrPubkey(recipient)` finds a stored mesh peerID (`"no peerID stored for recipient npub; cannot embed PM"`). Even with the gate relaxed, an internet-only contact has no mesh peerID. (A no-recipient embed variant already exists for geohash DMs: `encodePMForNostrNoRecipient`.)
3. **G3 — Internet messages are not post-quantum.** The embedded `bitchat1:` payload is a plaintext `PrivateMessagePacket` TLV protected only by NIP-17 gift-wrap encryption (X25519-classic). A harvest-now-decrypt-later adversary with a quantum computer can read every archived internet DM. **This is the biggest contradiction with the app's core promise.**
4. **G4 — Background delivery is fragile.** Relay WebSocket subscriptions are killed by Doze/background limits when the mesh FGS is not running.
5. **G5 — No presence/typing at account level.** Only geohash-scoped presence exists.
6. **G6 — Media over internet.** Relays commonly cap event size (~64 KB); photos/voice notes need fragmentation or blob delivery.
7. **G7 — No directory/onboarding.** No username or phone-number discovery; no invite links (the existing `deadair://verify` QR is a verification flow, not an add-contact flow).

## 3. Target architecture

```
┌──────────────┐   gift-wrapped, PQ-enveloped events (NIP-17)   ┌──────────────┐
│  Device A    │ ─────────────────────────────────────────────▶ │  Device B    │
│  (sender)    │   via Tor SOCKS (default ON)                   │  (receiver)  │
└──────┬───────┘                                                └──────▲───────┘
       │  local outbox (persisted)                                    │  background sub
       ▼                                                              │  (FGS lifecycle /
       │  Nostr relays (≥3, multi-relay fan-out, dedup) ──────────────┘  WorkManager poll)
       │          ▲                                                          │
       │          └── delivery ack / read receipt (gift-wrapped) ────────────┘
       └──────────── Mesh BLE/Wi-Fi transports remain a parallel path ────────┘
```

- **Backbone = public Nostr relays**, not a self-hosted server. Rationale: already integrated, already NIP-17-gift-wrapped, no hosting cost, decentralized, and the app's existing identity bridge (npub ↔ Noise key) makes recipients addressable by their existing identity. A self-hosted relay can be added later as an option (own-relay pinning) without protocol change.
- **Anonymity = Tor for the relay connection** (already default). Future: connect to `.onion` relay mirrors so the connection itself is Tor-to-Tor.
- **Offline queueing = the existing MessageRouter outbox**, extended to persist across process death (currently in-memory) and to flush when a peer's presence or a relay ack indicates they came online.

### 3.1 Message flow (target, Phase 1)

1. Sender composes a DM to `recipientNpub` (resolved from a contact card, QR, or prior mesh favorite).
2. Sender builds the payload: `PrivateMessagePacket` → **PQ one-way envelope** (§4) encrypted to the recipient's Noise static key (known from their identity/profile) → wraps as a `bitchat1:` payload → NIP-17 gift-wrap → publish to ≥3 relays (over Tor).
3. Recipient's background subscription receives the gift wrap, unwraps, opens the PQ envelope with their Noise static key, and routes the plaintext into the normal `MessageHandler` pipeline (same code path as mesh DMs → same UI, dedup, receipts).
4. Recipient sends a delivery ack and read receipt back through the same gift-wrap path. Sender's outbox clears on ack; TTL expiry remains the backstop.

## 4. Design decision: post-quantum one-way envelopes (closes G2/G3)

The mesh uses a **live** Noise XXhfs handshake; over the internet there is no live peer, so we use a **one-way** static-key hybrid envelope instead:

- Recipient's **Noise static public key** is already the canonical identity (peer IDs derive from it) and is bound to the npub via the existing proof-indexed favorites table.
- Sender encrypts with an ephemeral **X25519 + ML-KEM-768** hybrid KEM against the recipient's Noise static key, producing an AES-256-GCM ciphertext. This reuses the exact primitives from `noise/southernstorm/protocol/MLKEMDHState` (no new crypto code, no live handshake, no session state).
- Envelope layout (mirrors the existing `bitchat1:` prefix, new version tag):
  `bitchat2: <encKeyX25519(32)> <encKeyMLKEM(1184)> <iv(12)> <ciphertext+tag> <senderNoiseKey(32)> <ed25519sig>`
- Decryption is offline on the recipient's side: no relay interaction beyond receiving the event.
- **Result:** every internet DM is quantum-resistant at rest and in transit, matching the mesh guarantee. Receipts and acks use the same envelope.

> Why not a live Noise handshake over the relay? It doubles latency and requires both parties online simultaneously; the message is the handshake trigger. One-way envelopes give offline-first delivery with the same security property for confidentiality. (Authenticated identity still comes from the Ed25519 signature + noise key binding; out-of-band QR verification remains the trust anchor.)

## 5. Phased plan

### Phase 1 — Foundation: any-to-any delivery + PQ internet DMs (smallest change, biggest reachability win)

1. **Contact cards / add-by-identity (G1, G7)**
   - Shareable profile QR / deep link: `deadair://add?v=1&npub=…&noise=…&nick=…` (extend the existing `verify` QR plumbing in `MainActivity.handleVerificationIntent` + `VerificationService`).
   - `ContactDirectory` resolves an npub-only contact (no mesh peerID, not a favorite) into a first-class conversation.
2. **Relax the routing gate (G1)**
   - `canSendViaNostr` changes from `isMutualFavorite && nostrPubkey` to `nostrPubkey != null` (any known npub), with a per-conversation "pending verification" state shown in the UI (verified = QR; unverified = out-of-band trust, still E2EE + PQ).
   - Keep the mesh preference when a live mesh session exists.
3. **Recipient-less embed (G2)**
   - Generalize `NostrTransport` to send when the recipient has no stored mesh peerID (reuse the `NoRecipient` embed variant for global DMs, not just geohash). The sender's identity rides inside the envelope; the recipient learns the sender's peerID from the envelope's senderNoiseKey.
4. **PQ one-way envelope (G3)** — new `internet/PQEnvelope.kt` using existing ML-KEM + X25519 primitives; `NostrTransport` emits `bitchat2:` envelopes; recipient decrypt path added to `NostrDirectMessageHandler`.
5. **Persistent outbox (offline queueing hardening)** — persist the MessageRouter outbox (Room or the existing `AppStateStore` pattern) so queued messages survive process death; add ack-driven removal.

**Exit criteria:** two fresh installs, no mesh contact, exchange DMs + receipts over the internet with Tor on; sender's phone is killed and restarted mid-queue and the message still arrives; the DM cannot be decrypted by a relay operator (only ciphertext visible).

### Phase 2 — Experience parity

- **Account presence + typing (G5):** extend kind 20001 to account-level presence; typing indicators as lightweight sealed events; drive outbox flush on peer-presence.
- **Media over relay (G6):** fragment media into multiple gift-wrapped events (reuse `FragmentManager` semantics), assemble + verify on receipt; delivery acks per transfer.
- **Anti-spam for open inbox:** reuse the existing PoW machinery (`nostr/NostrProofOfWork`) for first-contact messages; rate-limit unknown senders; quarantine UI.

### Phase 3 — Scale / WhatsApp-parity

- **Push:** FCM data-message "sync trigger" (content stays E2EE; Google sees only a push token) or WorkManager periodic relay poll when the FGS isn't running; choose based on Play-policy posture.
- **Directory:** username registry (Nostr NIP-05 or kind 0 profiles) and optional phone-number sync as a later, opt-in step.
- **Groups over relay:** extend channel model to internet channels via kind 20000-style group events.
- **Own-relay option:** pin a self-hosted relay (and `.onion` mirror) per conversation for sovereignty; default remains public relays.
- **Multi-device:** single-device identity today; this is a large, separate workstream (device signing keys, sync protocol).

## 6. Security analysis

**Threat model:** relays are untrusted third parties; network is hostile (Tor exit / ISP); adversaries include harvest-now-decrypt-later with a quantum computer.

- **Confidentiality:** NIP-17 gift-wrap + (after Phase 1) the PQ one-way envelope → content is quantum-resistant and hidden from relays, ISPs, and Tor exits. Relays only ever see ciphertext.
- **Metadata:** gift-wraps carry the recipient in the outer `p` tag, so a relay learns "some sender → recipient npub, at time T". Mitigations: randomized timestamps already used (NIP-17 seals randomize `created_at` up to 2 days); optional padding to fixed event sizes; own-relay pinning removes third parties entirely.
- **Authenticity/spoofing:** a stranger can publish a gift-wrap *claiming* any sender pubkey; the recipient must trust the Ed25519 signature + embedded noise key binding. The existing out-of-band QR verification remains the anti-impersonation anchor, exactly as on mesh. First-contact messages are flagged unverified until then.
- **Spam:** open inbox (after G1) invites spam → PoW gate + unknown-sender quarantine (Phase 2).
- **Availability:** multi-relay fan-out + dedup already exists; outbox TTL bounds retries; ack-driven removal prevents duplicate delivery.
- **Key storage:** unchanged (hardware-wrapped keys per `IdentityKeystoreCipher`); the PQ envelope needs only the recipient's *public* noise key.

## 7. Open questions

1. FCM vs WorkManager for background sync (privacy vs. reliability tradeoff) — decision point in Phase 3.
2. Should first-contact messages be silently accepted into a "Requests" folder, or blocked until PoW passes?
3. Username directory: NIP-05 (domain-based, decentralized) vs. a simple registry — affects G7.

---

*Implementers: start with §5 Phase 1 items 1–5; each is independently shippable and testable.*
