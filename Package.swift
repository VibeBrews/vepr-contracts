// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "vepr-contracts",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "VeprSigning",
            targets: ["VeprSigning"]
        ),
        .library(
            name: "VeprDeviceAPI",
            targets: ["VeprDeviceAPI"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-openapi-generator.git", exact: "1.14.0"),
        .package(url: "https://github.com/apple/swift-openapi-runtime.git", exact: "1.13.0"),
        .package(url: "https://github.com/apple/swift-openapi-urlsession.git", exact: "1.3.2")
    ],
    targets: [
        .target(
            name: "VeprSigning",
            dependencies: []
        ),
        .target(
            name: "VeprDeviceAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
                .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession")
            ],
            plugins: [
                .plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")
            ]
        ),
        .testTarget(
            name: "VeprSigningTests",
            dependencies: [
                "VeprSigning"
            ],
            resources: [
                .copy("Resources/v1.json")
            ]
        )
    ],
    swiftLanguageModes: [.v6]
)
