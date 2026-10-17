# Campus BitChat Implementation State

## Overall Status

Current Phase: ALL PHASES COMPLETE
Completed Phases: 1-14
Next Phase: none — remaining work is deployment provisioning, not implementation
Overall Completion: implementation complete; NOT production-ready (see Security Review)

Last verified: 2026-09-03
Final Build: PASS (`:app:assembleDebug`, both campusMode values)
Final Tests: 644 passed / 0 failed (53 campus tests)
Device Verification: NOT VERIFIED — no device attached at verification time
Production Readiness: DEMO. `CampusConfig.trustedInstitutionKeys` is empty, so nothing verifies.

---

## Architecture Baseline

Actual repository components (verified by inspection, not assumed):

- MeshForegroundService: `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt` — UNMODIFIED
- BluetoothMeshService: `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt` — UNMODIFIED
- UnifiedMeshService: `mesh/MeshService.kt` — UNMODIFIED
- MessageRouter: `services/MessageRouter.kt` — UNMODIFIED
- Noise stack: `noise/` incl. `noise/southernstorm/` — UNMODIFIED
- EncryptionLayer: `crypto/EncryptionService.kt` — UNMODIFIED
- BitchatPacket: `protocol/BitchatPacket.kt` — UNMODIFIED (13-byte header untouched)
- Fragmentation: `mesh/FragmentManager.kt`, `mesh/FragmentingPacketSender.kt` — UNMODIFIED
- Identity: `identity/SecureIdentityStateManager.kt` (EncryptedSharedPreferences) — UNMODIFIED
- Compose navigation: THERE IS NO NavHost. UI is `MainActivity` -> `ui/ChatScreen.kt` + bottom sheets.
- Channel crypto: `ui/ChannelManager.kt` — PBKDF2-HMAC-SHA256, 100k iterations, NOT Argon2id.

Important architectural constraints honoured:
- No second Bluetooth/mesh/encryption implementation was created.
- Campus features are an application-layer overlay only.
- Geohash/Nostr/Tor/globe sources are INTACT and still compile; they simply have no entry
  point in the campus shell. Deleting them would have cascaded through hundreds of files.

---

## Phase 1 — Campus UI

Status: COMPLETE

Material 3 shell with bottom NavigationBar: Direct Messages / Classes / Alerts.
Class cards show code, department, member count, unread badge. Tapping a card silently performs
joinChannel + setChannelPassword — no IRC command is ever shown or typed.
The compositor escapes a leading "/" with a zero-width space so the IRC surface is unreachable.

Files created: campus/ui/CampusShell.kt, CampusClassesScreen.kt, CampusClassChatScreen.kt,
CampusDirectMessagesScreen.kt, CampusCommon.kt
Files modified: MainActivity.kt (shell switch + back routing), app/build.gradle.kts (CAMPUS_MODE)

Build: PASS   Tests: PASS

---

## Phase 2 — Campus Identity

Status: COMPLETE (mechanism); DEMO (no anchors provisioned)

Identity model: `CampusCredential` binds displayName + role + department to the holder's Ed25519
SIGNING PUBLIC KEY, signed by an institutional root key.

Name binding chain (each link already authenticated by upstream BitChat):
  message -> senderPeerID -> Noise fingerprint -> authenticated Ed25519 signing key -> credential

Role model: `CampusRole` = STUDENT | PROFESSOR | ADMIN. Role lives INSIDE the signed credential,
so editing it invalidates the signature (regression test exists).

Trusted institutional key: `CampusConfig.trustedInstitutionKeys` — PUBLIC keys only, hex, 32 bytes.
EMPTY by default => nothing verifies => everyone renders "Unverified user". Fail-closed by design.

Signature mechanism: Ed25519 via BouncyCastle (already a project dependency). No new primitive.
Canonicalisation: `CampusCanonical` length-prefixes every field, so encoding is injective — ad-hoc
delimiter concatenation would let one field bleed into another.

Files created: CampusCanonical.kt, CampusRole.kt, CampusCredential.kt (+ CampusCredentialVerifier),
CampusIdentityRegistry.kt, campus/ui/CampusIdentityRow.kt, campus/ui/CampusParticipantsSheet.kt

Build: PASS   Tests: PASS (11 identity tests)

DECISION: bound the credential to the SIGNING key, not the Noise fingerprint, because alert
signature verification needs the signing key anyway — one key, one chain.

---

## Phase 3 — Alerts

Status: COMPLETE

Signing: `CampusAlertSigner` loads the EXISTING device Ed25519 identity key from
SecureIdentityStateManager (EncryptedSharedPreferences). No new key material. Refuses to sign with a
key the credential does not name.

Verification: `CampusAlertVerifier` enforces TWO independent signatures, both required:
  1. alert body signed by the publishing key (covers id/title/body/timestamp/expiry/signer)
  2. publisher's credential signed by an institutional anchor, carrying an alert-publishing role
Plus an identity-match check so a genuine professor credential cannot be stapled to someone else's
alert (this is the `IdentityMismatch` case and has a dedicated test).

Trusted publisher configuration: derived from the credential's role, not a separate key list.
Forgery protection: verified by tests for body/title/timestamp tampering, role promotion,
credential theft, untrusted issuer, expiry, malformed and truncated signatures.

Wire format: message body envelope `BCA2:<base64url>` — NO packet format change.

Files created: CampusAlert.kt, CampusAlertVerifier.kt, CampusAlertSigner.kt,
campus/ui/CampusAlertComposer.kt, campus/ui/CampusAlertsScreen.kt

Build: PASS   Tests: PASS (11 alert tests)

---

## Phase 4 — File Picker

Status: COMPLETE

Supported MIME types: `CampusConfig.supportedFileMimeTypes` — pdf, doc, docx, ppt, pptx, text/plain.
Single source of truth read by picker, transfer validator and tests.

SAF: `ActivityResultContracts.OpenDocument` with the MIME array. No broad storage permission.
Coroutine/IO: read runs in `withContext(Dispatchers.IO)`. Size checked from OpenableColumns BEFORE
reading bytes, so a huge pick fails cleanly rather than OOMing.
Preview card (name + size, Cancel/Send) precedes sending.

NOTE: the stock `ui/media/FilePickerButton.kt` still copies on the main thread. It was left alone
(upstream, non-campus path). The campus picker is a separate file.

Files created: campus/ui/CampusDocumentPicker.kt, campus/ui/CampusTransferComponents.kt

Build: PASS   Tests: PASS

---

## Phase 5 — File Transfer

Status: COMPLETE

Chunk size: 32 KB (`CampusFileMessage.CHUNK_SIZE`)
Sliding window: 8 in flight (`WINDOW_SIZE`) — verified by a test that withholds all ACKs.
ACK mechanism: cumulative `highestContiguousIndex`; one ACK both slides the window and defines the
resume point. Stale/lower ACKs cannot rewind.
Resume: paused transfers re-offer (if the offer was lost) or restart from highestAcked+1.
Hash verification: SHA-256 from the Offer compared against the assembled file; on mismatch the file
is NEVER written or surfaced and a Cancel is sent.
Received files: `CampusReceivedFileStore` writes to app-private storage with a sanitised filename
(path-traversal stripped) and opens via the existing FileProvider — no filesystem path is exposed.

Layering: file -> 32 KB app chunk -> existing encryption -> existing transport -> existing BLE
fragmentation. BLE fragmentation and MTU logic were NOT touched.

THREE REAL BUGS FOUND AND FIXED DURING TESTING (all were genuine, not test artifacts):
 1. StackOverflow: a synchronous transport made pump() re-enter per chunk. Fixed with a
    pumping/pumpAgain re-entrancy guard.
 2. Infinite retransmit: once PAUSED the state never returned to ACTIVE, so every ACK rewound the
    window and resent forever. Fixed by setting ACTIVE on each successful send.
 3. Skipped chunk: a re-entrant ACK mutated nextIndex mid-pump, so the enclosing loop's increment
    skipped a chunk and the transfer could never complete. Rewind moved to the resume path only.

Files created: CampusFileProtocol.kt, CampusFileTransferService.kt, CampusReceivedFileStore.kt

Build: PASS   Tests: PASS (17 transfer tests)

---

## Phase 6 — Role-Based UI

Status: COMPLETE

Student: Direct Messages / Classes / Alerts. No Create Alert button. Cannot produce a verifying alert
even if the UI were bypassed — publishAlert re-checks the credential role before signing.
Professor: adds the Create Alert FAB and composer.
Admin: same as professor plus `canManageConfiguration` (no admin config screen was built — see
Outstanding Work; a fake unrestricted admin mechanism was deliberately NOT created).

Hidden from campus users: geohash globe, Tor/Arti settings, IRC commands, raw channel password
controls, mesh diagnostics. Implementations left INTACT.
Network status: peer count -> "Offline / Weak / Connected / Strong" + signal icon.

Build: PASS   Tests: PASS

---

## Phase 7 — Campus Configuration

Status: COMPLETE

`CampusConfig` centralises: institutionName, academicYear, classes, trustedInstitutionKeys,
supportedFileMimeTypes, alert lifetime/skew, INSTITUTIONAL_SECRET, and `isDemoDeployment`.

Demo configuration: 5 classes, empty trust anchors => isDemoDeployment == true.
Production configuration: replace `trustedInstitutionKeys` with real anchors and `classes` with a
roster. A test asserts every anchor is 64 hex chars (a public key), guarding against pasting a
private key into the config.

Build: PASS   Tests: PASS

---

## Phase 9 — verifyChannelPassword

Status: FIXED (investigated first, not blindly changed)

Investigation: only two callers, both in `ChannelManager.joinChannel`. The stub returned `true`
unconditionally. It is NOT a confidentiality break — a wrong password derives a wrong key and
decryption simply fails — but `joinChannel` reported success incorrectly and gave no feedback.
`channelKeyCommitments` was already declared and cleared but never populated or read: the intended
mechanism had been left unfinished.

Fix: `setChannelPassword` now records SHA-256 of the derived key as a commitment;
`verifyChannelPassword` compares a candidate's derived-key commitment against it.
Backward compatibility: when no commitment is known the function still returns true, because with no
server there is genuinely nothing to check against locally. Non-campus builds are unaffected.

File modified: ui/ChannelManager.kt

---

## Phase 10 — Build Flag

Status: COMPLETE

`-PcampusMode=true|false` -> `BuildConfig.CAMPUS_MODE`. Default true.
Both values verified to regenerate BuildConfig and build successfully.
campusMode=false renders the stock `ChatScreen` and constructs no campus objects.

---

## Security Review

Implemented protections:
- Two-signature alert chain (body signature + institution-signed role credential) with identity match
- Role carried inside the signed credential; promotion invalidates the signature
- Length-prefixed canonicalisation (injective) for everything signed
- Fail-closed everywhere: empty anchors verify nothing; unverifiable alerts are never downgraded to
  chat text; unverified peers never render a role
- Private keys only ever read from EncryptedSharedPreferences; never logged, never copied
- Zero Log statements in the campus module (verification failures are deliberately silent so no
  oracle reveals which anchor nearly matched)
- File offers validated (type/size/chunk-count consistency) before any allocation
- Received filenames sanitised against path traversal; files opened via FileProvider only
- Protocol envelopes filtered out of chat rendering

Potential vulnerabilities / limitations:
- INSTITUTIONAL_SECRET ships in the APK. It segregates class traffic from the general student body
  but is NOT enrolment authentication: anyone who unzips the APK can derive any class key.
- No roster enforcement. Any campus build can join any configured class.
- No credential revocation (only expiry). A compromised professor key stays valid until it lapses.
- Alert replay is possible within the alert's validity window; expiry bounds it, dedup does not exist.
- `maxAlertClockSkewSeconds` is defined but not yet enforced in the verifier.

Production blockers:
- trustedInstitutionKeys is EMPTY. Nothing verifies until campus IT provisions anchors.
- No credential provisioning flow. `installLocalCredential(token)` exists and validates correctly,
  but nothing calls it from the UI yet — there is no onboarding screen or QR scan.

---

## Testing

Latest build: `:app:assembleDebug` PASS (campusMode true and false)
Latest unit tests: 644 total, 0 failures, 0 errors. Campus: 53 tests across 4 classes.
  CampusIdentityTest 11, CampusAlertVerifierTest 11, CampusFileTransferTest 17, CampusConfigTest 14
Integration tests: none written.
Device testing: NOT PERFORMED — no device attached.

NOT unit-tested: `ChannelManager` (needs Android Context + full state graph), all Compose UI,
`CampusAlertSigner` (needs Context/EncryptedSharedPreferences), `CampusReceivedFileStore`.

---

## Outstanding Work

- Credential provisioning UI (QR scan or file import) calling `installLocalCredential`
- Admin configuration surface (`canManageConfiguration` is defined but unused)
- Roster enforcement so class membership is authorised, not just derivable
- Credential revocation list
- Alert replay dedup + enforce maxAlertClockSkewSeconds in CampusAlertVerifier
- Instrumented/device tests; on-device verification of the whole flow
- Optionally fix the main-thread copy in the stock `ui/media/FilePickerButton.kt`

---

## Next Action

Provision a real institutional key pair OFF-DEVICE (never in this repo), put the public key into
`CampusConfig.trustedInstitutionKeys`, issue one PROFESSOR credential for a test device using the
same canonical bytes as `CampusCredential.canonicalBytes()` (see `CampusTestKeys.issueCredential`
in the test source set for the exact procedure), then build a credential-import UI that calls
`CampusViewModel.installLocalCredential`. Until that exists the Alerts tab is permanently empty and
every user shows as unverified — which is correct, not a bug.
