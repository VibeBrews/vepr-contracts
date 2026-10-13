# Vepr — System Architecture

> Lives in `vepr-contracts/docs/ARCHITECTURE.md`. Every other repo reads it from `../vepr-contracts/docs/`.
> This is the source of truth for **what each repo owns and never does**. Mechanics (ports, headers, naming) are in `CONVENTIONS.md`. The exact bytes a phone signs are in `../signing/SPEC.md`.

## 1. What Vepr does

A bank (issuer) asks Vepr: "has this customer approved this payment?" Vepr asks the customer's phone. The customer sees the merchant and amount and approves with Face ID. The phone signs the exact transaction with a hardware-held key. Vepr verifies the signature and tells the bank.

- **Biometrics never leave the phone.** Face ID only unlocks a Secure Enclave key; Vepr only ever sees signatures and public keys. Vepr stores no biometric data.
- **Vepr never sees a card number.** The bank maps card → customer and sends only its own `customer_ref`. Card data stays out of Vepr (smaller PCI DSS scope).
- **A decision exists only if a valid signature, verified by `vepr-credentials`, was recorded by `vepr-authorization`.** Nothing else can create an approval.

## 2. Repos

| Repo | Language | Responsibility | Deployed? |
|---|---|---|---|
| `vepr-contracts` | specs + Java + Swift | The language every repo speaks: OpenAPI, protobuf (gRPC + events), signing spec + test vectors | No (published artifacts) |
| `vepr-java-kit` | Java | Shared plumbing for the Java services. No domain types. | No (library) |
| `vepr-authorization` | Java | **Decides.** Authorization state machine, pre-approvals | Yes |
| `vepr-credentials` | Java | **Vouches.** Devices, keys, attestation, enrollment codes, device tokens, signature verification | Yes |
| `vepr-channels` | Java | **Delivers.** APNs pushes to phones, signed webhooks to banks | Yes |
| `vepr-ios-sdk` | Swift | **Signs on the phone.** What banks embed | No (Swift package) |
| `vepr-authenticator-ios` | Swift | Vepr's own app; thin shell over the SDK; reference integration | App |
| `vepr-sandbox` | Java + React | Fake bank (issuer host), POS terminal, merchant QR page, virtual phone, e2e tests | Dev only |
| `vepr-infrastructure` | Compose, scripts | Runs everything locally with one command; later Terraform (Indian region) | — |

Originally sketched in Go; the services are Java (Spring Boot + Jdbi) because the team knows Java/Jdbi and the JVM ecosystem is strongest for this exact problem (App Attest verification, APNs, gRPC, Testcontainers). `vepr-go-kit` became `vepr-java-kit`. Boundaries are unchanged.

## 3. How it fits together

```
 Bank (sandbox issuer-host)                Vepr                                   Phone (SDK)
 ───────────────────────────    ─────────────────────────────────────    ─────────────────────────
 POST /v1/authorizations ──edge──▶ authorization ──gRPC/mTLS──▶ credentials
                                     │  (GetDevicesForCustomer)
                                     │ outbox → NATS: challenge.created
                                     ▼
                                   channels ──APNs (challenge_id only)──────────▶ wake
                                                                              GET challenge (device token)
                                                                              Face ID → approval key signs
                                   authorization ◀── POST decision {signature} ─┘
                                     │ rebuild payload from DB
                                     │ VerifySignature ──gRPC/mTLS──▶ credentials (live device status)
                                     │ conditional UPDATE + outbox: authorization.decided
                                     ▼
 webhook (HMAC signed) ◀──────── channels
```

All external HTTP enters through one **edge** reverse proxy (Caddy) that routes by path to the owning service (the bank and the phone each see one base URL). Service-to-service calls are gRPC over mTLS. Asynchronous facts travel as events: written to a service's `outbox` table in the same DB transaction as the state change, relayed to NATS JetStream, consumed idempotently.

## 4. Repo responsibilities

### 4.1 `vepr-contracts`
- `openapi/partner.yaml` (bank-facing), `openapi/device.yaml` (phone-facing), `proto/vepr/credentials/v1` (internal gRPC), `proto/vepr/events/v1` (events), `signing/` (canonical payload spec + vectors + reference encoders in Java and Swift), `docs/` (this file, CONVENTIONS.md, ERRORS.md).
- CI: lint, block breaking changes (`buf breaking`, OpenAPI diff), verify signing vectors in Java and Swift, publish Java artifacts (GitHub Packages) and tag the Swift package.
- **Never** contains business logic. The canonical encoder is part of the contract (like protobuf serialization), not business logic.

### 4.2 `vepr-java-kit`
- Spring Boot auto-configurations: bootstrap (virtual threads, JSON, problem details, request IDs, structured logs, OpenTelemetry), security (partner HMAC filter, device-JWT validation, mTLS + caller identity, rate limiting, tenant registry from a static file), db (Jdbi + Spring transactions, Flyway with a separate migrator role), events (transactional outbox, relay to JetStream, idempotent inbox, consumers), test helpers (Testcontainers Postgres 18 + NATS, test CA, HMAC/JWT helpers), Gradle conventions plugin.
- **Never** contains domain types (Authorization, Device, Challenge…). If it needs a business word, it belongs in a service.

### 4.3 `vepr-authorization` — the decision engine
- **Owns** (`authorization_db`, schema `authz`): `authorizations`, `pre_approvals`, `outbox`.
- **State machine:** `PENDING → APPROVED | DENIED | EXPIRED`, terminal forever. Every transition is a conditional `UPDATE … WHERE status = 'PENDING' … RETURNING`; a DB trigger also forbids updating a decided row.
- **Create (bank → `POST /v1/authorizations`):** HMAC-authenticated tenant → idempotency check (same key + same request → original result; same key + different request → `idempotency_key_reused`) → `GetDevicesForCustomer` (no active device → recorded `DENIED`, reason `no_active_device`) → **card tap:** consume a covering active pre-approval in one transaction → `APPROVED` instantly → **otherwise** insert `PENDING` with a 32-byte nonce and short TTL (card_present ≈ 25 s, qr ≈ 120 s, tenant-configurable within bounds), write `challenge.created`.
- **Decide (phone → `POST /v1/device/challenges/{id}/decision`):** load (scoped to the token's device + tenant) → reject expired/decided → rebuild the canonical payload **from its own row**, never from the request → `VerifySignature` (approve: approval key; deny: device key) **outside** any DB transaction → if credentials is unreachable, **fail closed**: never approve, return 503, the challenge stays `PENDING` and expires → conditional update storing the exact signed bytes + signature as evidence → `authorization.decided`.
- **Pre-approval (pre-arm):** phone requests a pre-approval challenge (server nonce, max amount ≤ tenant limit, ≤ 60 s) → signs it with the approval key (Face ID) → activated after `VerifySignature` → one active per device, one-time use, card_present only.
- **Worker:** sweeps expired `PENDING` → `EXPIRED` (and pre-approvals) with `FOR UPDATE SKIP LOCKED`, publishing decisions.
- **Never:** stores keys, verifies cryptography itself, sends pushes or webhooks.

### 4.4 `vepr-credentials` — the device and key registry
- **Owns** (`credentials_db`, schema `creds`): `devices`, `enrollment_codes`, `device_auth_challenges`, `outbox`. Holds the device-token signing key (ES256) and publishes JWKS.
- **Two keys per phone:**
  - **Device key** (Secure Enclave, no biometric): proves "an enrolled phone is calling". Signs `DEVICE_AUTH` (token issuance) and `deny` decisions.
  - **Approval key** (Secure Enclave, `.biometryCurrentSet`): signs only `approve` decisions and pre-approvals. Destroyed by the OS if Face ID enrollment changes → SDK reports `needsReenrollment`.
- **Enrollment:** bank calls `POST /v1/enrollments` for an authenticated customer → one-time code (10 chars, Crockford base32, 10 min, stored only as HMAC with a server pepper, issuing a new code voids older ones) → app generates both keys, gets an App Attest attestation whose `clientDataHash` = SHA-256 of the `ENROLLMENT_BINDING` bytes (binds tenant, code and both public keys) → app sends code + keys + attestation → credentials verifies code (single use), attestation (Apple root, app ID allowlist, environment), key shapes → in one transaction consumes the code, revokes the customer's previous active device (one active device per customer), inserts the device, writes `device.enrolled` (+ `device.revoked` for the replaced one).
- **Attestation policy per tenant:** `required` (production) or `allow_none` (sandbox only: virtual phone, iOS Simulator). Recorded on the device row; production tenants never accept `none`.
- **Device tokens:** challenge → device-key signature over `DEVICE_AUTH` → ES256 JWT (5 min). Other services validate locally via JWKS.
- **`VerifySignature(tenant, device, key_type, payload, signature)`:** live read of device status (no cache) → tenant match → payload header/type allowed for that key type (format check only; it never interprets amounts) → ECDSA P-256 verify → `valid` + reason.
- **Revocation:** bank → `POST /v1/devices/{id}/revoke` → `device.revoked`. Bank lists devices via `GET /v1/devices?customer_ref=`.
- **Never:** knows about transactions, merchants or amounts.

### 4.5 `vepr-channels` — delivery
- **Owns** (`channels_db`, schema `chan`): `push_tokens`, `push_deliveries`, `webhook_deliveries`, `processed_events`. The only holder of the APNs key.
- `challenge.created` → APNs alert push (priority 10, time-sensitive, expires with the challenge, collapse ID = challenge ID). Payload carries **only** the challenge ID; the app fetches details from authorization.
- `authorization.decided` (and later `device.*`) → webhook to the tenant's URL, HMAC-SHA256 over timestamp + body, retries with exponential backoff + jitter, event ID makes delivery idempotent, dead-lettered after the retry budget.
- `device.revoked` → delete the push token.
- Device endpoint `POST /v1/device/push-tokens`.
- **Never** decides anything. A fully compromised channels can send fake notifications but cannot create an approval.

### 4.6 `vepr-ios-sdk`
- Swift package banks embed. KeyManager (two Secure Enclave keys), App Attest wrapper, APIClient (generated from `device.yaml`, device-token refresh, TLS SPKI pinning), Signer (uses the contracts' Swift encoder; verified by the vectors), ReviewScreen (merchant, amount, expiry from the server's challenge — what you see is what you sign), push helpers.
- Public API: `Vepr.configure`, `enroll(code:)`, `registerPushToken`, `pendingChallenges`, `present(challengeID:) -> Decision`, `preApprove(maxAmount:duration:)`, `status`, `reset`.
- **Never** depends on the authenticator app; never contains checkout or QR scanning.

### 4.7 `vepr-authenticator-ios`
- Thin SwiftUI app using only the SDK's public API: enrollment (type or scan code), home (pending + device status), approval (SDK screen), pre-arm, QR scanner (AVFoundation; sends a payment intent to the **sandbox bank**, playing the bank app's role for demos), settings. Push registration and deep-linking into a challenge.

### 4.8 `vepr-sandbox`
- Uses only Vepr's public APIs. **If the sandbox needs a back door, the API is incomplete.**
- `issuer-host` (Java): test customers + test cards (card → customer; never sent to Vepr), bank-login page that issues enrollment codes and can revoke devices, receives POS card authorizations and QR payment intents, calls the partner API, verifies webhooks, times out and declines. **No balances or ledger for now** (money simulation deferred).
- `pos-terminal` (React): amount, test card, **Tap**, countdown, Approved/Declined.
- `merchant-page` (React): creates an order, shows a signed QR, flips to Paid/Declined.
- `virtual-device` (Java): a software phone (software P-256 keys, attestation `none`, sandbox tenants only) with a small web page so the full flow can be demoed on a laptop and run in CI without an iPhone.
- `e2e-tests`: QR payment, inline tap, pre-armed tap, timeout, revoked device, replayed signature, wrong-amount signature, tampered QR, idempotent retry, forged webhook.

### 4.9 `vepr-infrastructure`
- `docker-compose.yml`: Postgres 18 (three databases, per-service migrator + app roles), NATS JetStream (per-service publish/subscribe permissions), the three services (published image tags), edge (Caddy), sandbox apps, optional observability (Grafana LGTM).
- Override file to build one service from a local checkout. Scripts: local CA + mTLS certs, secrets, tenants file. Makefile: `up`, `down`, `seed`, `logs`, `certs`, `secrets`, `reset`.
- Later: Terraform in an Indian cloud region (RBI data localisation), deployment manifests.

## 5. Key flows (all must be covered by e2e tests)

1. **Enrollment:** bank login → `POST /v1/enrollments` → code shown → app `POST /v1/device/enrollments` (code, 2 SPKIs, attestation) → device active → `device.enrolled`.
2. **Device token:** `POST /v1/device/token-challenges` → sign `DEVICE_AUTH` with device key → `POST /v1/device/tokens` → JWT.
3. **Button (POS tap) without pre-arm:** POS → issuer → `POST /v1/authorizations` (card_present) → `PENDING` → push → phone fetches → Face ID → approve → webhook → POS shows Approved before the countdown ends.
4. **Pre-armed tap:** phone pre-approves ₹X for 60 s → POS tap ≤ ₹X → instant `APPROVED`; a second tap needs a fresh approval.
5. **QR:** merchant page shows signed QR → app scans → issuer `payment-intent` → `POST /v1/authorizations` (qr) → same approval path → merchant page shows Paid.
6. **Deny:** phone denies (device key) → `DENIED`.
7. **Timeout:** no answer → sweeper → `EXPIRED` → webhook → declined.
8. **Revocation:** bank revokes → approvals with that device fail immediately (live check), push token deleted.

## 6. Security invariants (tests must prove each)

1. A signature for transaction A is never accepted for transaction B (nonce + all fields + type + version are signed; payload rebuilt server-side).
2. A decided authorization never changes (conditional updates + DB trigger).
3. The approval key alone can approve; the device key can only authenticate and deny.
4. A revoked device fails verification on the very next call (no caches).
5. Credentials outage ⇒ no approvals (fail closed).
6. Push and webhook payloads never carry amounts, merchant names, signatures or tokens beyond what the receiver needs; logs never contain signatures, nonces, tokens, codes or push tokens.
7. No service reads another service's database; each app DB role has DML only on its own schema.
8. Production tenants require App Attest; `none` attestation is impossible outside sandbox tenants.

## 7. Performance budget (server side, excluding APNs and the human)

| Step | p95 target |
|---|---|
| `POST /v1/authorizations` (incl. gRPC device lookup) | < 60 ms |
| Commit → event on NATS (outbox relay nudged after commit) | < 50 ms |
| Phone fetches challenge | < 40 ms |
| Decision incl. `VerifySignature` | < 80 ms |
| Decision → webhook sent | < 100 ms |

Techniques: virtual threads, small Hikari pools with statement timeouts, partial indexes on hot `PENDING` paths, local JWT verification (cached JWKS), gRPC keep-alive channels, outbox relay woken on commit with a 1 s poll fallback, no network calls inside DB transactions.

## 8. POC vs MVP

The POC is the MVP's first slice, not a throwaway: same repos, contracts, schemas and security model. POC shortcuts are behind explicit flags that production config cannot enable:

| POC shortcut | Flag / place | MVP replacement |
|---|---|---|
| Static `tenants.yaml` | kit `TenantRegistry` (file impl) | tenants service / DB impl of same interface |
| `none` attestation for virtual phone / Simulator | tenant `attestation.policy: allow_none` | `required` everywhere outside sandbox |
| Logging push sender when no APNs key | channels `vepr.apns.enabled=false` | APNs |
| Foreground polling in the app | authenticator setting | push only (polling as backup) |
| HTTP (not TLS) between edge and services inside compose | kit `vepr.http.tls.enabled=false` | TLS everywhere |
| Secrets from `.env` | env vars | cloud secret manager / KMS |
