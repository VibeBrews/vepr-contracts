import Foundation

public enum Channel: String, Sendable, CaseIterable {
    case cardPresent = "card_present"
    case qr = "qr"

    public var wire: String {
        rawValue
    }

    public static func fromWire(_ wire: String) throws -> Channel {
        guard let channel = Channel(rawValue: wire) else {
            throw SigningError.invalidArgument("Unknown channel wire value: \(wire)")
        }
        return channel
    }
}

public enum Platform: String, Sendable, CaseIterable {
    case ios = "ios"
    case virtual = "virtual"

    public var wire: String {
        rawValue
    }

    public static func fromWire(_ wire: String) throws -> Platform {
        guard let platform = Platform(rawValue: wire) else {
            throw SigningError.invalidArgument("Unknown platform wire value: \(wire)")
        }
        return platform
    }
}

public enum Decision: UInt8, Sendable, CaseIterable {
    case approve = 0x01
    case deny = 0x02

    public var wire: UInt8 {
        rawValue
    }

    public static func fromWire(_ wire: UInt8) throws -> Decision {
        guard let decision = Decision(rawValue: wire) else {
            throw SigningError.invalidArgument(String(format: "Unknown decision byte: 0x%02x", wire))
        }
        return decision
    }
}

public enum PayloadType: UInt8, Sendable, CaseIterable {
    case challengeDecision = 0x01
    case preApproval = 0x02
    case deviceAuth = 0x03
    case enrollmentBinding = 0x04

    public var wire: UInt8 {
        rawValue
    }

    public static func fromWire(_ wire: UInt8) throws -> PayloadType {
        guard let type = PayloadType(rawValue: wire) else {
            throw SigningError.invalidArgument(String(format: "Unknown payload type byte: 0x%02x", wire))
        }
        return type
    }
}

public enum KeyType: String, Sendable, CaseIterable {
    case device = "device"
    case approval = "approval"

    public var wire: String {
        rawValue
    }

    public static func fromWire(_ wire: String) throws -> KeyType {
        guard let keyType = KeyType(rawValue: wire.lowercased()) else {
            throw SigningError.invalidArgument("Unknown key type wire value: \(wire)")
        }
        return keyType
    }
}
