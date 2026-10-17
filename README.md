# Campus BitChat

Campus BitChat is an offline-first messaging app designed specifically for college campuses. It allows students, faculty, and staff to stay connected when conventional networks fail—both in campus dead zones where cellular data and SMS fail, and during large-scale campus events where network congestion collapses communication.

---

## The Problem

On many college campuses, network connectivity is unreliable or nonexistent in specific areas:
- **Campus Dead Zones**: Basements, labs, lecture halls with heavy concrete construction, and peripheral campus zones often have zero cellular coverage. Standard messaging tools like WhatsApp, Telegram, and standard Android SMS fail completely.
- **Large Events and Congestion**: Major campus festivals and symposiums (such as Takshashila, bringing over 10,000 visitors and participants onto campus) cause severe cellular network congestion. The shared infrastructure becomes unusable. Faculty and organizers cannot broadcast room changes, bus schedules, or safety notices, and students cannot coordinate with one another.

Existing solutions fall short:
- Conventional chat apps require working cellular data or Wi-Fi infrastructure.
- Generic off-grid mesh tools are completely anonymous and built around raw IRC-style terminal commands, channel passwords, and hex IDs, making them impractical for general campus adoption.

Campus BitChat solves this by establishing a decentralized, localized communication layer using the phones already present on campus.

---

## What It Does

Campus BitChat focuses on two core functionalities:

### 1. Verified Staff Bulletins
Authorized faculty and administrative staff can compose short, cryptographically signed bulletins (e.g., lecture venue updates, exam schedules, transport announcements, safety notices, and event schedules).
- **Offline Mesh Relay**: Bulletins propagate peer-to-peer over Bluetooth Low Energy (BLE) mesh hops without internet access.
- **Offline Retention & Late Pickup**: Participating devices retain active bulletins in local storage. When a student enters the area or opens the app later, their device can retrieve the unexpired bulletin from nearby peers.
- **Honest Scoping**: This is best-effort offline dissemination, not a guaranteed life-safety emergency alert system.

### 2. Peer-to-Peer & Group Messaging
Students and staff can engage in 1-to-1 direct messaging and organized group chats:
- **Delivery States**: Tracks message progression (`Sending`, `Sent`, `Delivered`, `Read` where supported) with delivery receipts.
- **Encrypted Group Chats**: Group communication protected by symmetric AES-GCM group encryption with direct, 1-to-1 encrypted invite delivery.
- **College-Linked Identity**: Identities are grounded in official college email verification to avoid anonymous trolling and confusion.
- **Local Persistence**: Conversations and bulletins are persisted locally on the device using SQLite/Room.

---

## Architecture

Campus BitChat is built as an additive campus application overlay on top of the open-source **[BitChat](https://github.com/permissionlesstech/bitchat-android)** Android BLE mesh engine. The core radio, routing, and transport layers are intentionally preserved to maintain stability and cross-platform wire compatibility.

```
┌────────────────────────────────────────────────────────┐
│                   Campus BitChat UI                    │
│   (Material 3: Chats, Contacts, Groups, Profile)       │
├──────────────────────────┬─────────────────────────────┤
│      Campus Layer        │       Messaging Layer       │
│  • Verified Bulletins    │  • Direct Messages (Receipts│
│  • Institutional Roles   │  • Encrypted Group Chats    │
│  • Email Verification    │  • Presence & Contacts      │
│  • Sliding-Window File Tx│  • Local Room Persistence   │
├──────────────────────────┴─────────────────────────────┤
│         BitChat BLE Mesh & Cryptographic Core         │
│  • BLE Mesh Transport & Background Service             │
│  • Multi-Hop TTL Routing & Adaptive Duty Cycling       │
│  • Noise Protocol (XX Handshake, ChaCha20-Poly1305)    │
│  • Packet Fragmentation, Reassembly & Deduplication    │
└────────────────────────────────────────────────────────┘
```

### Reused from Upstream BitChat
- **Bluetooth Low Energy (BLE) Mesh**: Direct peer-to-peer mesh networking without routers or base stations.
- **Multi-Hop Relay**: Dynamic multi-hop message forwarding with TTL routing (practical limit of ~7 hops) and adaptive duty cycling for battery conservation.
- **Noise Protocol Framework**: Authenticated encryption sessions using the Noise XX handshake pattern (`X25519` key exchange, `ChaCha20-Poly1305` AEAD).
- **Packet Fragmentation & Deduplication**: Splitting application payloads to fit BLE MTU constraints and de-duplicating forwarded mesh packets via `FragmentManager` and `BitchatPacket`.
- **Background Mesh Service**: Android foreground service (`MeshForegroundService`) maintaining persistent mesh connectivity within OS power management limits.

### Built in This Project
- **Modern Tabbed Messenger UI**: Clean Material 3 interface (`MessengerShell`) with dedicated **Chats**, **Contacts**, **Groups**, and **Profile** screens, replacing raw IRC slash commands and diagnostic menus.
- **Campus Identity & Role Model**: Role definition (`STUDENT`, `PROFESSOR`, `ADMIN`) in `com.bitchat.android.campus.CampusRole` with length-prefixed injective canonical serialization to prevent role-promotion attacks.
- **Dual-Signature Bulletin Verification**: `CampusAlertVerifier` and `CampusAlertSigner` requiring dual cryptographic validation:
  1. The bulletin payload signed by the publisher's identity key.
  2. The publisher's role credential signed by an institutional authority.
- **Direct Messaging Engine**: `DirectMessageManager` and `DirectMessageRepository` managing delivery states, receipt acknowledgments, and conversation threads.
- **Encrypted Group Management**: Secure group creation, AES-GCM group encryption (`GroupCipher`), and private group invitations (`GroupInvite`) transmitted over direct 1-to-1 encrypted channels.
- **Sliding-Window File Transfer**: `CampusFileTransferService` implementing 32 KB chunked transfers, an 8-chunk sliding window, cumulative ACKs, and SHA-256 pre-write integrity checks.
- **Local Persistence Layer**: Room database (`MessagingDatabase`) for local message retention, threads, and offline bulletin caching.
- **Upstream Security Fix**: Hardened `ChannelManager.verifyChannelPassword` with key-commitment validation.
- **Configurable Build Flag**: `-PcampusMode=true|false` (`BuildConfig.CAMPUS_MODE`) allowing switching between the campus messenger and standard upstream BitChat.

---

## Verification Model

Because college campuses rarely provide a ready-made public key infrastructure (PKI) for students, Campus BitChat uses a pragmatic, fail-closed verification pipeline:

1. **Email-Based Community Proof**: Users prove affiliation with the institution by verifying their official college email address.
2. **Explicit Role Promotion**: Staff privileges (e.g., `PROFESSOR` or `ADMIN`) are granted through an explicit institutional approval/promotion step after email verification. College email alone is not sufficient to issue campus-wide bulletins.
3. **Device-Level Cryptographic Signatures**: When an authorized staff member posts a bulletin, it is signed using their device's Ed25519 signing key.
4. **Offline Recipient Validation**: Receiving devices verify both the bulletin signature and the author's role credential entirely offline against configured trust anchors.
5. **Fail-Closed Protection**: If a signature is invalid, expired, or tampered with, the bulletin is silently rejected or flagged as unverified; it is never presented as an authentic campus bulletin.

---

## Key Features

- **True Offline Operation**: Fully operational messaging and bulletin broadcasting over BLE mesh when internet and cellular SMS are down.
- **Authenticated Staff Bulletins**: Official announcements signed by faculty/staff with offline verification.
- **Bulletin Cache & Late Arrival Sync**: Unexpired bulletins are cached on local devices so arriving students can sync missed announcements from peers.
- **1-to-1 Direct Messaging**: Private conversations with end-to-end delivery tracking (`Sending`, `Sent`, `Delivered`, `Read`).
- **Encrypted Group Chats**: Group communication with automatic channel membership synchronization and private symmetric key sharing.
- **Presence Awareness**: Real-time peer discovery and status (`Online`, `Recently Active`, `Offline`) over the mesh.
- **Reliable Offline File Sharing**: Transfer lecture notes, timetables, and documents (.pdf, .docx) using sliding-window chunked transfer.
- **Familiar UI**: Modern, intuitive Material 3 design requiring no knowledge of mesh protocols or terminal commands.

---

## Limitations (Intentional Engineering Trade-offs)

- **Best-Effort Delivery**: Mesh delivery relies on physical radio proximity and peer availability. It is not an SLA-backed, guaranteed emergency broadcasting system.
- **Hop & Density Limits**: Practical multi-hop relay is bounded (typically up to ~7 hops). Dense crowds can saturate BLE advertising channels, requiring adaptive relay throttling.
- **Battery & Background Constraints**: Android OS limits on background BLE scanning mean message propagation is fastest when devices are active or in the foreground.
- **Time-Bound Bulletins**: Bulletins have an explicit expiration timestamp. If no awake device holding a valid copy is nearby, a student entering the area after the fact cannot receive it.
- **Authorization Barrier**: Holding a campus email gives student messaging access; publishing bulletins strictly requires an explicit promotion step.

---

## Tech Stack

- **Language**: Kotlin 2.x
- **UI Framework**: Jetpack Compose (Material 3)
- **Local Storage**: Room / SQLite, EncryptedSharedPreferences
- **Asynchronous & Reactive**: Kotlin Coroutines & StateFlow
- **Cryptography**: BouncyCastle (Ed25519), Noise Protocol Framework (XX pattern, X25519, ChaCha20-Poly1305), SHA-256
- **Mesh & Networking**: Android Bluetooth Low Energy (BLE), Wi-Fi Aware, BitChat Mesh Core
- **Build System**: Gradle with Kotlin DSL (`build.gradle.kts`)

---

## Getting Started

### Prerequisites
- Android Studio Ladybug (or newer)
- Android SDK (API 26+ minimum, target SDK 35)
- JDK 21
- **Physical Android devices** (BLE mesh advertising and multi-hop scanning cannot be properly tested on Android emulators)

### Building from Source

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd campus-bitchat-android
   ```

2. Build the debug APK with Campus Mode enabled (default):
   ```bash
   ./gradlew :app:assembleDebug
   ```

   *(Optional: To compile the upstream stock BitChat interface instead, pass `-PcampusMode=false`)*:
   ```bash
   ./gradlew :app:assembleDebug -PcampusMode=false
   ```

3. Install on connected hardware:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Permissions**: On first launch, grant Bluetooth, Nearby Devices, and Notification permissions as prompted by the onboarding coordinator.

### Running Tests

```bash
# Run all unit and protocol tests
./gradlew testDebugUnitTest

# Run code style and lint analysis
./gradlew lintDebug
```

---

## Project Status

Campus BitChat is actively maintained as an offline campus communication system on top of the BitChat mesh foundation. It is tested for dead-zone communication and resilient campus event coordination where conventional networks fail.
