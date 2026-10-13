import Foundation

public enum KeyRules {
    private static let expectedMagic: [UInt8] = [0x56, 0x45, 0x50, 0x52] // "VEPR"
    private static let expectedVersion: UInt8 = 0x01

    /// Checks whether the given key type is permitted to sign the provided payload according to SPEC §5.
    ///
    /// Validates the header (magic, version, type) and, for `CHALLENGE_DECISION` (type 0x01),
    /// inspects the trailing decision byte. Does not parse any other fields.
    ///
    /// - Parameters:
    ///   - payload: the raw payload bytes
    ///   - keyType: the key type (.device or .approval)
    /// - Returns: true if allowed, false if not allowed
    /// - Throws: `SigningError.invalidArgument` if the header is invalid, version/type is unknown,
    ///   or the decision byte is invalid.
    public static func isAllowed(_ payload: Data, keyType: KeyType) throws -> Bool {
        let bytes = [UInt8](payload)

        guard bytes.count >= 6 else {
            throw SigningError.invalidArgument(
                "Payload length (\(bytes.count)) is shorter than required 6-byte header"
            )
        }

        // Header check: magic
        for i in 0..<4 {
            if bytes[i] != expectedMagic[i] {
                throw SigningError.invalidArgument(String(
                    format: "Invalid magic byte at offset %d: expected 0x%02x, got 0x%02x",
                    i, expectedMagic[i], bytes[i]
                ))
            }
        }

        // Header check: version
        let version = bytes[4]
        if version != expectedVersion {
            throw SigningError.invalidArgument(String(
                format: "Unsupported payload version: 0x%02x (expected 0x%02x)",
                version, expectedVersion
            ))
        }

        // Header check: type
        let type = try PayloadType.fromWire(bytes[5])

        switch type {
        case .challengeDecision:
            guard bytes.count >= 7 else {
                throw SigningError.invalidArgument(
                    "CHALLENGE_DECISION payload too short to contain trailing decision byte"
                )
            }
            let decisionByte = bytes[bytes.count - 1]
            let decision = try Decision.fromWire(decisionByte)
            switch decision {
            case .approve:
                return keyType == .approval
            case .deny:
                return keyType == .device
            }
        case .preApproval:
            return keyType == .approval
        case .deviceAuth:
            return keyType == .device
        case .enrollmentBinding:
            return false // Hashed, never signed by Vepr keys
        }
    }
}
