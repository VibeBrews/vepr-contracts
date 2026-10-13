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
