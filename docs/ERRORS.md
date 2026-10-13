# Vepr Error Catalog

> Lives in `vepr-contracts/docs/ERRORS.md`. Authoritative reference for all error codes across partner and device APIs.

All error responses conform to **RFC 9457 Problem Details** (`application/problem+json`).

## RFC 9457 Problem Format

```json
{
  "type": "https://errors.vepr.io/challenge_expired",
  "title": "Challenge expired",
  "status": 409,
  "code": "challenge_expired",
  "request_id": "01925b6a-742e-7bb2-8098-b789ef012345",
  "detail": "The biometric approval challenge has expired.",
  "errors": []
}
```

- `type`: URI identifying the error problem type.
- `title`: Short human-readable summary.
- `status`: HTTP status code matching the response.
- `code`: Stable machine-readable error string in `snake_case`.
- `request_id`: Tracing UUIDv7 matching `X-Request-Id`.
- `detail`: Optional additional context (never contains sensitive tokens, keys, codes, or customer PII).
- `errors`: Optional array of `{ "field": "...", "message": "..." }` for validation failures.

---

## Error Codes Table

| Code | HTTP | Meaning | When Returned | Client Retry Strategy |
|---|---|---|---|---|
| `invalid_request` | 400 | Request body, query, or path validation failed | Missing required fields, values out of bounds, malformed UUIDv7/RFC3339 strings, unknown JSON fields, payload > 64 KiB | **Do not retry** without correcting the request. |
| `idempotency_key_missing` | 400 | Required Idempotency-Key header is absent | Partner calls to `POST /v1/authorizations` without `Idempotency-Key` header | **Do not retry** without supplying a valid header (1–64 characters `[A-Za-z0-9_-]`). |
| `invalid_enrollment_code` | 400 | Enrollment code is unknown, already consumed, or expired | Device calls `POST /v1/device/enrollments` with an invalid/expired code | **Do not retry** with the same code. Customer must request a fresh code from bank issuer. |
| `attestation_failed` | 400 | App Attest verification failed | Apple App Attest certificate chain, environment, app ID, or `clientDataHash` mismatch | **Do not retry** automatically. Requires app diagnostic / environment fix. |
| `attestation_required` | 400 | Attestation is required but missing or set to `none` | Production tenant receives enrollment request without genuine App Attest receipt | **Do not retry** unless valid hardware attestation can be produced. |
| `unauthenticated` | 401 | Authentication failed or missing | Missing/expired/invalid device JWT token, or missing/invalid partner HMAC headers (`Vepr-Signature`) | **Retry after re-authenticating**: phone refreshes device token via `POST /v1/device/tokens`; bank fixes HMAC signing. |
| `forbidden` | 403 | Caller authenticated but unauthorized for resource | Device token tenant mismatch, requesting a challenge not belonging to this device, or partner key lacks permissions | **Do not retry**. Action forbidden by authorization rules. |
| `invalid_signature` | 403 | Biometric or device signature verification failed | Signature over canonical payload failed ECDSA P-256 verification against registered SPKI | **Do not retry**. The signing attempt is invalid. |
| `device_revoked` | 403 | Target device is revoked/inactive | Attempting to sign or access challenges for a revoked device | **Do not retry**. Device must be re-enrolled with a new code. |
| `not_found` | 404 | Resource does not exist or belongs to another tenant/device | Non-existent challenge ID, enrollment, or device (also returned instead of 403 to prevent resource enumeration) | **Do not retry** unless the resource ID is known to have been created later. |
| `already_decided` | 409 | Authorization state is no longer PENDING | Attempting to submit a decision or cancel an authorization that is already `APPROVED`, `DENIED`, or `EXPIRED` | **Do not retry**. State is terminal. |
| `challenge_expired` | 409 | Challenge has passed its expiration timestamp | Client submits decision after `expires_at` | **Do not retry**. Authorization has expired; transaction must be restarted. |
| `idempotency_key_reused` | 422 | Same idempotency key used with different request payload | Bank calls `POST /v1/authorizations` with a previously used key but altered payload parameters | **Do not retry** with the same key. Generate a new `Idempotency-Key` for distinct requests. |
| `amount_out_of_range` | 422 | Transaction amount exceeds tenant limits or is <= 0 | Authorization requested with `amount_minor` exceeding tenant `max_amount_minor` | **Do not retry** unless the transaction amount is reduced. |
| `currency_not_supported` | 422 | Currency is not supported for tenant | Authorization requested with a currency not in the tenant's configured currency list | **Do not retry** without changing to an allowed currency. |
| `rate_limited` | 429 | Rate limit exceeded | Request rate exceeds Bucket4j tenant or device threshold | **Retry after delay**: Honor the `Retry-After` response header (seconds). |
| `dependency_unavailable` | 503 | Downstream dependency or database temporarily unreachable | Service-to-service gRPC timeout, database pool saturation | **Retry with exponential backoff and jitter**. |
| `verification_unavailable` | 503 | Signature verification service unreachable during decision | `vepr-authorization` fails to contact `vepr-credentials` over gRPC; fails closed | **Retry once or twice** quickly before challenge expiration, or allow challenge to expire. |
| `internal_error` | 500 | Unexpected internal server failure | Uncaught exception or server-side panic (stack traces never leaked) | **Retry with exponential backoff**. |
