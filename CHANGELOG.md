# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - Unreleased

### Added
- Repository bootstrap with skeleton directories and configuration.
- Base documentation: ARCHITECTURE.md, CONVENTIONS.md, ERRORS.md, and AGENTS.md.
- Signing specification v1.
- Bank-facing Partner REST API specification (`openapi/partner.yaml`) with HMAC-SHA256 request signing and webhook definitions.
- Phone-facing Device REST API specification (`openapi/device.yaml`) with token-challenge exchange, challenge signing (type 0x01), and pre-approvals (type 0x02).
- Shared OpenAPI components (`openapi/components/common.yaml`) for RFC 9457 Problem details, standard error responses, and merchant models.
- Redocly configuration (`.redocly.yaml`) enforcing strict validation including `additionalProperties: false` on all object schemas.
- Breaking-change verification script (`scripts/check-breaking.sh`) and Swift spec synchronization (`scripts/sync-swift.sh`).
- Internal gRPC contract: `CredentialService` (`VerifySignature`, `GetDevicesForCustomer`) in `proto/vepr/credentials/v1/credentials.proto`.
- NATS JetStream event definitions (`ChallengeCreated`, `AuthorizationDecided`, `DeviceEnrolled`, `DeviceRevoked`) in `proto/vepr/events/v1/events.proto`.
- Buf configuration (`buf.yaml`) enforcing `STANDARD` lint and `FILE` breaking rules.
- Java multi-project Gradle build (Kotlin DSL, Java 25 toolchain, version catalog) publishing to GitHub Packages and mavenLocal:
  - `contracts-proto`: generates protobuf messages and gRPC stubs (`io.vepr:vepr-contracts-proto`).
  - `contracts-openapi`: packages OpenAPI specifications as resources with `OpenApiSpecs` classpath provider (`io.vepr:vepr-contracts-openapi`).
  - `contracts-signing`: canonical signing payload encoder (`PayloadEncoder`), key rules verifier (`KeyRules`), strict-DER ECDSA signature verifier (`SignatureVerifier`), enrollment codes normalizer (`EnrollmentCodes`), and deterministic test vector generator (`VectorGenerator`).
- Committed canonical signing test vectors (`signing/vectors/v1.json`) and test keys (`TEST-ONLY-p256.pem`, `TEST-ONLY-p256-other.pem`) covering positive, negative, and encoder rejection cases with RFC 6979 deterministic signatures.
- Automated vector drift verification (`verifyVectorsUpToDate`) wired into Gradle check.
- Swift package (`Package.swift`, Swift 6.2 toolchain) targeting iOS 17 and macOS 14:
  - `VeprSigning`: pure Foundation + CryptoKit reference encoder (`PayloadEncoder`), key rules verifier (`KeyRules`), and enrollment code normalizer (`EnrollmentCodes`).
  - `VeprDeviceAPI`: generated Swift OpenAPI client for `openapi/device.yaml` using pinned `swift-openapi-generator` 1.14.0, `swift-openapi-runtime` 1.13.0, and `swift-openapi-urlsession` 1.3.2.
  - `VeprSigningTests`: Swift Testing suite verifying all 23 canonical test vectors from `signing/vectors/v1.json` and unit tests.
- Extended Swift sync script (`scripts/sync-swift.sh`) syncing both `openapi/device.yaml` and `signing/vectors/v1.json` with strict `--check` mode.
