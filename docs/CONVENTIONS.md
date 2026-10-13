# Vepr Conventions

> Lives in `vepr-contracts/docs/CONVENTIONS.md`. Mechanics every repo follows. If a repo needs something not covered here, propose an addition here first.

## 1. Workspace and naming

- All repos are siblings: `~/vepr/vepr-contracts`, `~/vepr/vepr-java-kit`, `~/vepr/vepr-authorization`, … Repos reference each other only through published artifacts, never relative source paths, except the optional local Gradle composite build (§11).
- Java group / root package: `io.vepr` (e.g. `io.vepr.authorization`). Swift module names: `Vepr`, `VeprSigning`, `VeprDeviceAPI`.
- Service names (also DNS names in compose, mTLS identities, OTel service names): `authorization`, `credentials`, `channels`, `edge`, `issuer-host`, `virtual-device`.

## 2. Versions (pin exact patch versions; check for the latest patch before pinning; no snapshots/milestones)

| Component | Version line |
|---|---|
| Java | 25 LTS (Eclipse Temurin) |
| Spring Boot | 4.1.x (4.0 OSS support ends 31 Dec 2026) — includes Spring Framework 7, Spring Security 7.1, Spring gRPC, Jackson 3 |
| Jdbi | latest 3.x (`jdbi3-core`, `-sqlobject`, `-postgres`, `-spring`) |
| PostgreSQL | 18 (`postgres:18` image). Move to 19 only after its first minor release. |
| Flyway, HikariCP, Testcontainers, Micrometer, OpenTelemetry | as managed by Spring Boot 4.1 |
| NATS | server `nats:2` with JetStream; client `io.nats:jnats` latest |
| Protobuf | `buf` CLI; `protoc-gen-grpc-java` |
| Bouncy Castle | `bcprov-jdk18on`, `bcpkix-jdk18on` latest |
| App Attest verification | webauthn4j App Attest module (confirm current artifact id on Maven Central) |
| APNs | Pushy (`com.eatthepath:pushy`) or JDK `HttpClient` HTTP/2 fallback |
| Rate limiting | Bucket4j (in-memory now; Postgres-backed later) |
| Swift / Xcode | Swift 6 language mode, latest stable Xcode; iOS deployment target 17.0 |
| OpenAPI for Swift | `apple/swift-openapi-generator`, `swift-openapi-runtime`, `swift-openapi-urlsession` |
| Web (sandbox) | Node 24 LTS, pnpm, Vite, React 19, TypeScript strict |

Android target API 36 is the Play requirement as of 31 Aug 2026 (relevant when an Android SDK is added).

## 3. Local ports

| Component | Port(s) |
|---|---|
| edge (Caddy) | `8443` https (local CA), `8080` http (local only) |
| authorization | `8081` http, `9181` management (actuator) |
| credentials | `8082` http, `9092` gRPC (mTLS), `9182` management |
| channels | `8083` http, `9183` management |
| issuer-host | `8090` |
| virtual-device | `8095` |
| pos-terminal / merchant-page (dev servers) | `5173` / `5174` |
| Postgres / NATS / NATS monitor | `5432` / `4222` / `8222` |
| Grafana LGTM (optional) | `3000` UI, `4317` OTLP gRPC, `4318` OTLP HTTP |

Management ports are never routed by the edge.

## 4. HTTP APIs

- **Two audiences, two prefixes:** partner (bank) API under `/v1/...`; device (phone) API under `/v1/device/...`.
- Edge routing:

| Path | Service |
|---|---|
| `/v1/authorizations*` | authorization |
| `/v1/device/challenges*`, `/v1/device/pre-approval*` | authorization |
| `/v1/enrollments*`, `/v1/devices*` | credentials |
| `/v1/device/enrollments`, `/v1/device/token-challenges`, `/v1/device/tokens` | credentials |
| `/v1/device/push-tokens` | channels |
| `/sandbox/*`, `/bank*` | issuer-host — **local compose with the sandbox profile only**, so one tunnel URL serves both Vepr and the fake bank to a real iPhone; never in any deployed environment |
| anything else | 404 at the edge |

- JSON, `snake_case` fields, UTF-8, `Content-Type: application/json`. Unknown request fields are rejected (`invalid_request`). Max request body 64 KiB (edge + app).
- IDs: UUIDv7, lowercase canonical string. Times: RFC 3339 UTC with milliseconds and `Z` (`2026-10-13T04:18:27.123Z`). Responses include `server_time` wherever the client shows a countdown.
- Money: `amount_minor` (integer, paise for INR) + `currency` (ISO 4217). Never floats.
- Binary values (keys, signatures, nonces, attestation): base64url without padding.
- `Idempotency-Key` header (1–64 chars `[A-Za-z0-9_-]`) is **required** on `POST /v1/authorizations`. Same key + same request → same response; same key + different request → `422 idempotency_key_reused`. Keys are scoped per tenant, retained ≥ 24 h. `POST /v1/enrollments` and `POST /v1/devices/{id}/revoke` are repeat-safe by design instead (a new code voids the previous one, because codes are stored only as HMACs and can never be shown twice; revoking a revoked device returns it unchanged).
- Every response carries `X-Request-Id` (accepted from the caller if it is a valid UUID, otherwise generated) and a W3C `traceparent` is propagated.
- Errors are RFC 9457 `application/problem+json`:

```json
{ "type": "https://errors.vepr.io/challenge_expired", "title": "Challenge expired",
  "status": 409, "code": "challenge_expired", "request_id": "…", "detail": "optional, never sensitive" }
```

## 5. Error codes (`docs/ERRORS.md` holds the full table)

| Code | HTTP | Meaning |
|---|---|---|
| `invalid_request` | 400 | validation failed (field errors listed in `errors[]`) |
| `idempotency_key_missing` | 400 | partner POST without key |
| `unauthenticated` | 401 | missing/invalid credentials, token or HMAC |
| `forbidden` | 403 | authenticated but not allowed |
| `invalid_signature` | 403 | signature did not verify |
| `device_revoked` | 403 | device no longer active |
| `not_found` | 404 | also used for "exists but not yours" |
| `already_decided` | 409 | authorization not `PENDING` |
| `challenge_expired` | 409 | past `expires_at` |
| `idempotency_key_reused` | 422 | same key, different request |
| `amount_out_of_range`, `currency_not_supported` | 422 | tenant limits |
| `invalid_enrollment_code` | 400 | one error for unknown/used/expired codes |
| `attestation_failed`, `attestation_required` | 400 | enrollment attestation problems |
| `rate_limited` | 429 | with `Retry-After` |
| `dependency_unavailable`, `verification_unavailable` | 503 | fail-closed paths |
| `internal_error` | 500 | never leaks stack traces |

## 6. Authentication

### 6.1 Partner (bank → Vepr): HMAC request signing
Headers: `Vepr-Key-Id`, `Vepr-Timestamp` (Unix seconds), `Vepr-Signature: v1=<lowercase hex>`.
String to sign (joined with `\n`): `v1`, timestamp, HTTP method (upper-case), path including query exactly as sent, lowercase hex SHA-256 of the raw body (empty body → hash of empty string).
`signature = HMAC-SHA256(secret_for_key_id, string_to_sign)`. Reject if `|now − timestamp| > 300 s`. Compare in constant time. Secrets are per key id, resolved from the tenant registry; two active keys per tenant allow rotation. Later: mTLS for banks in addition.

### 6.2 Device (phone → Vepr): short-lived device token
`Authorization: Bearer <JWT>`. ES256, issued by credentials, `kid` header, claims: `iss=vepr-credentials`, `aud=vepr-device-api`, `sub=<device_id>`, `tnt=<tenant_id>`, `iat`, `exp` (5 min), `jti`. Services validate locally against credentials' JWKS (`http://credentials:8082/.well-known/jwks.json`, internal only, cached ≤ 10 min, refreshed on unknown `kid`). Tokens are never logged.

### 6.3 Service to service: mTLS
gRPC only, over mTLS. Certificates from the local CA (`vepr-infrastructure/scripts/gen-certs.sh`) carry `DNS:<service>` and `URI:spiffe://vepr.local/svc/<service>`. The server authorises the caller by URI SAN per method (e.g. only `authorization` may call `CredentialService/*`).

### 6.4 Webhooks (Vepr → bank)
Headers: `Vepr-Event-Id`, `Vepr-Event-Type`, `Vepr-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(webhook_secret, "<t>.<raw body>")>`. Receivers reject `|now − t| > 300 s` and dedupe on `Vepr-Event-Id`. Body:

```json
{ "id": "<event_id>", "type": "authorization.decided", "created_at": "…",
  "data": { "authorization_id": "…", "bank_reference": "…", "status": "APPROVED", "decision_reason": "approved_by_customer", "decided_at": "…" } }
```

## 7. Events

- Protobuf messages in `proto/vepr/events/v1`, each with `EventMeta meta = 1` (`event_id` UUIDv7, `event_type`, `tenant_id`, `occurred_at`, `producer`).
- NATS JetStream stream `VEPR_EVENTS`, subjects `vepr.events.<type>`: `vepr.events.challenge.created`, `vepr.events.authorization.decided`, `vepr.events.device.enrolled`, `vepr.events.device.revoked`. File storage, max age 7 days, duplicate window 2 min.
- Message headers: `Nats-Msg-Id = event_id` (JetStream dedupe), `Vepr-Event-Type`, `traceparent`, `Content-Type: application/x-protobuf`.
- Producers only publish through the transactional outbox. Consumers are durable pull consumers named `<service>-<event-type>` (e.g. `channels-challenge-created`), record `event_id` in `processed_events` in the same transaction as their effect, ack after commit, and dead-letter to `vepr.dlq.<service>.<type>` after max deliveries.
- Events carry the minimum data the consumers need. `challenge.created` carries no amount or merchant.

## 8. Databases

- One database per service: `authorization_db` (schema `authz`), `credentials_db` (schema `creds`), `channels_db` (schema `chan`). Never `public`.
- Two roles per service, named after the service: `<service>_migrator` owns the schema and runs Flyway; `<service>_app` has `USAGE` on the schema and only the DML each table needs (no `DELETE` unless required, never DDL). Examples: `authorization_migrator`, `authorization_app`, `credentials_app`, `channels_app`. Migrations grant to the Flyway placeholder `${app_role}`, never a hard-coded name. `PUBLIC` has no privileges.
- Connection defaults: `statement_timeout=5s`, `idle_in_transaction_session_timeout=10s`, `lock_timeout=2s`, `application_name=<service>`; Hikari max pool 10.
- Types: `uuid` ids (generated in Java as UUIDv7), `timestamptz` only, `bigint` money, `text` + `CHECK` constraints for enums, `bytea` for keys/nonces/signatures. Every table has `created_at`.
- Flyway files `V<n>__<snake_desc>.sql`, never edited after merge; repeatable `R__` only for views/functions.

## 9. Configuration and secrets

- Spring properties under `vepr.*`; every secret comes from an environment variable or a mounted file, never from a committed file. `.env` and `secrets/` are git-ignored.
- Shared env names: `VEPR_DB_URL`, `VEPR_DB_APP_USER`, `VEPR_DB_APP_PASSWORD`, `VEPR_DB_MIGRATOR_USER`, `VEPR_DB_MIGRATOR_PASSWORD`, `VEPR_NATS_URL`, `VEPR_NATS_USER`, `VEPR_NATS_PASSWORD`, `VEPR_TENANTS_FILE`, `VEPR_TLS_DIR` (`ca.crt`, `<svc>.crt`, `<svc>.key`), `VEPR_CREDENTIALS_GRPC_TARGET`, `VEPR_CREDENTIALS_JWKS_URL`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`.
- Service-specific: credentials `VEPR_JWT_SIGNING_KEY_FILE`, `VEPR_JWT_SIGNING_KEY_ID`, `VEPR_ENROLLMENT_CODE_PEPPER`; channels `VEPR_APNS_ENABLED`, `VEPR_APNS_KEY_FILE`, `VEPR_APNS_KEY_ID`, `VEPR_APNS_TEAM_ID`, `VEPR_APNS_TOPIC`.
- Tenant secrets are referenced from `tenants.yaml` by env var name (`secret_env: …`), never inlined.
- Profiles: `local` (developer machine), `test`, `compose`, `prod`. `prod` refuses to start if any POC shortcut flag is on.

### 9.1 `tenants.yaml` shape

```yaml
tenants:
  - id: bank_sandbox
    display_name: Sandbox Bank
    status: active
    partner_keys:
      - key_id: sbx_key_1
        secret_env: VEPR_TENANT_BANK_SANDBOX_KEY_1
    webhook:
      url: http://issuer-host:8090/sandbox/webhooks/vepr
      secret_env: VEPR_TENANT_BANK_SANDBOX_WEBHOOK_SECRET
    attestation:
      policy: allow_none            # production tenants: required
      allowed_app_ids: ["ABCDE12345.io.vepr.authenticator"]
      allow_development_environment: true
    challenge_ttl_seconds: { card_present: 25, qr: 120 }
    pre_approval: { enabled: true, max_amount_minor: 200000, max_duration_seconds: 60 }
    limits: { max_amount_minor: 10000000, currencies: [INR] }
```

## 10. Logging, metrics, tracing

- JSON structured logs (Spring Boot structured logging, ECS format) with `trace_id`, `span_id`, `request_id`, `tenant_id`, and entity ids. Never log: signatures, nonces, tokens, enrollment codes, HMAC secrets, push tokens, request/response bodies, customer names.
- OpenTelemetry traces across HTTP, gRPC, Jdbi and NATS (trace context stored in outbox headers so async hops join the trace). Metrics via Micrometer: decision latency, outcomes by reason, outbox lag, webhook attempts, push results, verification failures by reason.

## 11. Build, publishing, CI

- Java: Gradle (Kotlin DSL) + version catalog, applying the `io.vepr.java-conventions` plugin from `vepr-java-kit` (toolchain 25, Spotless + palantir-java-format, Error Prone + NullAway with JSpecify, JaCoCo, CycloneDX SBOM).
- Artifacts: GitHub Packages (Maven) for `vepr-contracts-*` and `vepr-kit-*`; GHCR images `ghcr.io/<org>/vepr-<service>:<semver>` and `:sha-<short>`. Swift: git tags on `vepr-contracts` and `vepr-ios-sdk`.
- Local cross-repo work: a git-ignored `local.properties` flag (`vepr.localKit=true`) makes `settings.gradle.kts` use `includeBuild("../vepr-java-kit")` / `../vepr-contracts/java` instead of published versions.
- SemVer everywhere. Contracts: additive = minor; breaking = major (and `/v2` paths for REST).
- Conventional Commits. Every repo's CI runs on PRs: build, tests (Testcontainers), lint/format check, dependency vulnerability scan, and for deployables an image build + container scan. Main branch is protected.

## 12. Security baseline (every repo)

- No secrets in git, ever (enable GitHub secret scanning + push protection).
- Validate every input at the boundary (sizes, enums, formats); reject unknown fields.
- Constant-time comparison for HMACs, codes and tokens.
- `SecureRandom` only for nonces, codes and ids that must be unguessable.
- Containers run as non-root with read-only root filesystem where possible.
- Dependencies pinned; Dependabot/Renovate enabled; no unmaintained crypto libraries.
- Personal data minimisation (DPDP Act): store `customer_ref`, never names, phone numbers or card numbers in Vepr services.
