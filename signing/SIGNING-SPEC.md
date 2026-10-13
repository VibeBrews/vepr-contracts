# Vepr Canonical Signing Format — v1

> Lives in `vepr-contracts/signing/SPEC.md`. This is the most important file in the system.
> If the phone and the server disagree on one byte, every signature fails. If the format is loose, one transaction's signature can be replayed for another. **Change it only by adding a new version.**

## 1. Cryptography

| Item | Value |
|---|---|
| Algorithm | ECDSA over NIST P-256 (secp256r1) with SHA-256 |
| What is signed | The payload bytes defined below (the signer hashes them with SHA-256 as part of ECDSA; do **not** pre-hash) |
| Signature encoding | ASN.1 DER `SEQUENCE { r INTEGER, s INTEGER }` (what CryptoKit `derRepresentation` and Java `SHA256withECDSA` produce). Verifiers reject anything that is not strict DER. |
| Public key encoding | X.509 `SubjectPublicKeyInfo` DER, `id-ecPublicKey` + `prime256v1`, uncompressed point. Verifiers reject other curves, compressed points and points not on the curve. |
| Transport encoding | base64url **without padding** in JSON for keys, signatures, nonces and attestation objects |
| Malleability | ECDSA signatures are malleable `(r, s)` ↔ `(r, n−s)`. Never use signature bytes as an identifier or dedupe key. |

P-256 is chosen because it is the only curve the iOS Secure Enclave supports and it is equally available in Android Keystore/StrongBox later.

## 2. Primitive encodings

All multi-byte integers are **big-endian**.

| Primitive | Encoding |
|---|---|
| `u8` | 1 byte |
| `i64` | 8 bytes, two's complement |
| `uuid` | 16 bytes, RFC 9562 byte order (the canonical hex string's bytes, in order) |
| `nonce32` | exactly 32 raw bytes |
| `str` | `u16` byte length, then UTF-8 bytes. Non-empty, max 1024 bytes. Encode **exactly the UTF-8 bytes received from the server** — never re-normalise, trim or case-fold on the client. Servers store all signed strings NFC-normalised at ingestion and reject strings containing control characters (U+0000–U+001F, U+007F–U+009F). |
| `blob` | `u16` byte length, then raw bytes. Max 1024 bytes. |
| `time_ms` | `i64` Unix epoch milliseconds (UTC) |

## 3. Header (every payload)

| Offset | Field | Encoding | Value |
|---|---|---|---|
| 0 | magic | 4 bytes | ASCII `VEPR` = `56 45 50 52` |
| 4 | version | `u8` | `0x01` |
| 5 | type | `u8` | see §4 |

Domain separation comes from the header: the same key can never be tricked into signing one type that is accepted as another.

## 4. Payload types

Fields follow the header **in exactly this order**, with no separators and no trailing bytes.

### 4.1 `0x01 CHALLENGE_DECISION`

| # | Field | Encoding | Source of truth |
|---|---|---|---|
| 1 | tenant_id | `str` | authorization row |
| 2 | challenge_id | `uuid` | authorization row id |
| 3 | device_id | `uuid` | the device the challenge targets |
| 4 | nonce | `nonce32` | authorization row (CSPRNG, 32 bytes) |
| 5 | merchant_id | `str` | authorization row |
| 6 | merchant_name | `str` | authorization row (shown on screen) |
| 7 | amount_minor | `i64` | authorization row, > 0 |
| 8 | currency | `str` | ISO 4217 upper-case, e.g. `INR` |
| 9 | channel | `str` | `card_present` or `qr` |
| 10 | expires_at | `time_ms` | authorization row |
| 11 | decision | `u8` | `0x01` approve, `0x02` deny |

### 4.2 `0x02 PRE_APPROVAL`

| # | Field | Encoding |
|---|---|---|
| 1 | tenant_id | `str` |
| 2 | pre_approval_id | `uuid` (server-issued) |
| 3 | device_id | `uuid` |
| 4 | nonce | `nonce32` (server-issued) |
| 5 | max_amount_minor | `i64`, > 0 |
| 6 | currency | `str` |
| 7 | channel_scope | `str`, v1 allows only `card_present` |
| 8 | expires_at | `time_ms` (server-set, ≤ 60 s after issue) |

### 4.3 `0x03 DEVICE_AUTH`

| # | Field | Encoding |
|---|---|---|
| 1 | tenant_id | `str` |
| 2 | challenge_id | `uuid` (device-auth challenge id) |
| 3 | device_id | `uuid` |
| 4 | nonce | `nonce32` |
| 5 | expires_at | `time_ms` |

### 4.4 `0x04 ENROLLMENT_BINDING` (hashed, never signed by Vepr keys)

`clientDataHash` passed to App Attest `attestKey` = `SHA-256(ENROLLMENT_BINDING bytes)`. The server rebuilds these bytes from the request and must obtain the same hash.

| # | Field | Encoding |
|---|---|---|
| 1 | tenant_id | `str` |
| 2 | enrollment_code | `str` — normalised form: upper-case, hyphens/spaces removed, `O→0`, `I→1`, `L→1` |
| 3 | platform | `str` — `ios` (or `virtual` for the sandbox phone) |
| 4 | device_key_spki | `blob` |
| 5 | approval_key_spki | `blob` |

## 5. Which key may sign what

| Key | May sign | Must never sign |
|---|---|---|
| Approval key (Face ID) | `CHALLENGE_DECISION` with decision `0x01`; `PRE_APPROVAL` | `DEVICE_AUTH`; `CHALLENGE_DECISION` with `0x02` |
| Device key (no biometric) | `DEVICE_AUTH`; `CHALLENGE_DECISION` with decision `0x02` | `CHALLENGE_DECISION` with `0x01`; `PRE_APPROVAL` |

`vepr-credentials.VerifySignature` enforces this table by reading the header type (and the final decision byte for type `0x01`) — a format check, not business logic. Signers (SDK, virtual device) enforce it too.

## 6. Verifier rules (server)

1. Rebuild the payload from the server's own stored values. Never parse a payload sent by the client.
2. Reject if `now ≥ expires_at` (server clock) before verifying.
3. Reject unknown magic, version or type; reject trailing bytes.
4. Verify with the stored SPKI for the correct key type; reject non-DER signatures.
5. Nonces are single-use: the state transition that consumes them is a conditional update.

## 7. Test vectors

`signing/vectors/v1.json` contains, for every type:

- `name`, `type`, the input fields (strings as given, `uuid` as canonical lowercase hex, `nonce32` as hex, times as integers),
- `payload_hex` (the exact bytes) and `payload_sha256_hex`,
- `public_key_spki_b64url` of the **test key** in `signing/vectors/TEST-ONLY-p256.pem`, and `signature_der_b64url` that must verify,
- `must_verify: true|false`.

Three kinds of vectors are required:

- **Verification failures** (`must_verify: false`): amount changed by 1; different nonce; decision byte flipped; type byte changed; version `0x02`; trailing byte appended; signature in raw `r||s` (64-byte) form; signature valid but made by the other key.
- **Edge cases that must verify** (`must_verify: true`): merchant name with multi-byte UTF-8 (e.g. `चाय वाला ☕`); amount `1`; amount `9000000000000000` (large i64).
- **Encoder rejections** (`expect_encoder_error: true`, no payload): a merchant name that is not NFC-normalised; an empty string field; a string over 1024 bytes; a control character; amount `0`; a lower-case currency; a nonce that isn't 32 bytes. Every `str` field must be non-empty.

Every implementation (Java, Swift, virtual device) runs all vectors in CI. A vector file change requires a version bump unless it only adds vectors.

## 8. Reference implementations

- Java: `io.vepr.contracts.signing` (`vepr-contracts-signing` artifact) — `PayloadEncoder` with one static method per type returning `byte[]`, `SignatureVerifier` (SPKI + DER), `KeyRules`.
- Swift: `VeprSigning` target in the contracts Swift package — `PayloadEncoder` mirroring the Java API, returning `Data`.
- Both are pure, dependency-free, deterministic, and fully covered by the vectors.
