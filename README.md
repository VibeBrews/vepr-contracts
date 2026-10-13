# vepr-contracts

The single source of truth for every message that crosses a repo boundary in Vepr (biometric payment approval):
- **OpenAPI**: Bank-facing partner REST API and phone-facing device REST API.
- **Protobuf**: Internal gRPC services (`CredentialService`) and NATS JetStream event definitions.
- **Signing**: Canonical v1 binary payload specification, test vectors, and reference encoders.

---

## Consuming Contracts

### Java / JVM Services

Contracts are published to **GitHub Packages** under the `io.vepr` group:

```kotlin
// settings.gradle.kts or build.gradle.kts
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/vepr-io/vepr-contracts")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").getOrElse("")
            password = providers.environmentVariable("GITHUB_TOKEN").getOrElse("")
        }
    }
}

dependencies {
    implementation("io.vepr:vepr-contracts-openapi:<version>")
    implementation("io.vepr:vepr-contracts-proto:<version>")
    implementation("io.vepr:vepr-contracts-signing:<version>")
}
```

For local multi-repo development, add `vepr.localKit=true` to `local.properties` to use Gradle composite builds against local sibling checkouts.

### iOS / Swift Package Manager

In your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/vepr-io/vepr-contracts.git", from: "<version>")
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
└── scripts/                        # Utility scripts (sync-swift.sh)
```

---

## Commands & Verification

- **Lint OpenAPI**: `npx @redocly/cli lint openapi/*.yaml`
- **Lint Proto**: `buf lint`
- **Check Breaking Changes**: `oasdiff breaking <last-tag-spec> openapi/<file>.yaml` and `buf breaking --against '.git#tag=<last-tag>'`
- **Java Build & Tests**: `cd java && ./gradlew check`
- **Swift Tests**: `swift test`
- **Sync Swift Device Spec**: `scripts/sync-swift.sh` (`--check` in CI)
