# vepr-contracts

The single source of truth for every message that crosses a repo boundary in Vepr (biometric payment approval):
- **OpenAPI**: Bank-facing partner REST API and phone-facing device REST API.
- **Protobuf**: Internal gRPC services (`CredentialService`) and NATS JetStream event definitions.
- **Signing**: Canonical v1 binary payload specification, test vectors, and reference encoders.

---

## Consuming Contracts

### Java / JVM Services

Contracts are published to **GitHub Packages** under the `io.vepr` group:
- `io.vepr:vepr-contracts-openapi:<version>`
- `io.vepr:vepr-contracts-proto:<version>`
- `io.vepr:vepr-contracts-signing:<version>`

#### Maven / Gradle Configuration

Configure authentication in your consumer build file (`settings.gradle.kts` or `build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/VibeBrews/vepr-contracts")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR") ?: ""
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}

dependencies {
    implementation("io.vepr:vepr-contracts-openapi:0.1.0")
    implementation("io.vepr:vepr-contracts-proto:0.1.0")
    implementation("io.vepr:vepr-contracts-signing:0.1.0")
}
```

For local development, add your GitHub credentials to `~/.gradle/gradle.properties`:
```properties
gpr.user=<your-github-username>
gpr.key=<github-personal-access-token>
```
> [!NOTE]
> The GitHub Personal Access Token requires the `read:packages` scope (classic PAT). In CI environments, set `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables.

For local multi-repo development across sibling checkouts, add `vepr.localKit=true` to `local.properties` to use Gradle composite builds.

### iOS / Swift Package Manager

Requires **Xcode 26** (Swift tools version 6.2+).

In your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/VibeBrews/vepr-contracts.git", from: "0.1.0")
],
targets: [
    .target(
        name: "MyTarget",
        dependencies: [
            .product(name: "VeprSigning", package: "vepr-contracts"),
            .product(name: "VeprDeviceAPI", package: "vepr-contracts")
        ]
    )
]
```

Products provided:
- `VeprSigning`: Cryptographic reference encoder and verifier for canonical signing v1 payloads.
- `VeprDeviceAPI`: Generated Swift client for device-facing OpenAPI (`openapi/device.yaml`).

---

## Repository Layout

```
├── openapi/                        # OpenAPI 3.1 specs (partner.yaml, device.yaml)
├── proto/                          # Protocol Buffers (gRPC & NATS events)
│   └── vepr/
│       ├── credentials/v1/         # CredentialService gRPC
│       └── events/v1/              # NATS JetStream domain events
├── signing/                        # Canonical signing format v1
│   ├── SPEC.md                     # Binary encoding specification
│   └── vectors/                    # Canonical test vectors (JSON + test keys)
├── java/                           # Gradle modules (contracts-openapi, -proto, -signing)
├── Sources/                        # Swift package targets
│   ├── VeprSigning/                # Reference Swift canonical payload encoder
│   └── VeprDeviceAPI/              # Swift OpenAPI client generated from device.yaml
├── Tests/                          # Swift tests & vector verification
├── docs/                           # Architecture, conventions, and error codes
└── scripts/                        # Utility scripts (sync-swift.sh, check-breaking.sh, release-notes.sh)
```

---

## Commands & Verification

- **Lint OpenAPI**: `npx @redocly/cli lint openapi/*.yaml`
- **Lint Proto**: `buf lint`
- **Check Breaking Changes**: `./scripts/check-breaking.sh`
- **Java Build & Tests**: `cd java && ./gradlew check`
- **Swift Tests**: `swift test` (requires Xcode 26 / Swift 6.2+)
- **Sync Swift Device Spec**: `scripts/sync-swift.sh` (`--check` in CI)

---

## Releasing

Releases are tag-driven and automated via GitHub Actions (`.github/workflows/release.yml`):

1. **Update CHANGELOG.md**: Move unreleased entries under `## [X.Y.Z] - YYYY-MM-DD`.
2. **Commit changes**: Commit the updated changelog and any release preparation files.
3. **Create and push annotated tag**:
   ```bash
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin vX.Y.Z
   ```
4. **Automated Release Execution**:
   - The `ci` workflow runs full validation across all contracts (lint, breaking, sync, Java, Swift).
   - The `publish` job validates tag format, extracts changelog notes with `scripts/release-notes.sh`, and publishes Java artifacts (`io.vepr:vepr-contracts-*`) to GitHub Packages.
   - The `github-release` job publishes the GitHub Release with the extracted release notes.
   - SwiftPM consumers consume the tagged release directly from git.

> [!WARNING]
> Breaking changes are forbidden without a major version bump, an entry in `CHANGELOG.md`, and explicit approval per `AGENTS.md`. `oasdiff` and `buf breaking` checks must pass against the previous release tag.
