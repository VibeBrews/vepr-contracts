import Foundation

public enum PayloadEncoder {
    private static let magic: [UInt8] = [0x56, 0x45, 0x50, 0x52] // "VEPR"
    private static let version: UInt8 = 0x01

    public static func challengeDecision(
        tenantId: String,
        challengeId: UUID,
        deviceId: UUID,
        nonce: Data,
        merchantId: String,
        merchantName: String,
        amountMinor: Int64,
        currency: String,
        channel: Channel,
        expiresAt: Date,
        decision: Decision
    ) throws -> Data {
        try validateAmount(amountMinor)
        try validateCurrency(currency)

        var out = Data()
        writeHeader(&out, .challengeDecision)
        try writeStr(&out, tenantId, "tenant_id")
        writeUuid(&out, challengeId)
        writeUuid(&out, deviceId)
        try writeNonce32(&out, nonce)
        try writeStr(&out, merchantId, "merchant_id")
        try writeStr(&out, merchantName, "merchant_name")
        writeI64(&out, amountMinor)
        try writeStr(&out, currency, "currency")
        try writeStr(&out, channel.wire, "channel")
        try writeTimeMs(&out, expiresAt)
        writeU8(&out, decision.wire)
        return out
    }

    public static func preApproval(
        tenantId: String,
        preApprovalId: UUID,
        deviceId: UUID,
        nonce: Data,
        maxAmountMinor: Int64,
        currency: String,
        channelScope: Channel,
        expiresAt: Date
    ) throws -> Data {
        try validateAmount(maxAmountMinor)
        try validateCurrency(currency)
        guard channelScope == .cardPresent else {
            throw SigningError.invalidArgument(
                "channel_scope v1 allows only card_present, got: \(channelScope.wire)"
            )
        }

        var out = Data()
        writeHeader(&out, .preApproval)
        try writeStr(&out, tenantId, "tenant_id")
        writeUuid(&out, preApprovalId)
        writeUuid(&out, deviceId)
        try writeNonce32(&out, nonce)
        writeI64(&out, maxAmountMinor)
        try writeStr(&out, currency, "currency")
        try writeStr(&out, channelScope.wire, "channel_scope")
        try writeTimeMs(&out, expiresAt)
        return out
    }

    public static func deviceAuth(
        tenantId: String,
        challengeId: UUID,
        deviceId: UUID,
        nonce: Data,
        expiresAt: Date
    ) throws -> Data {
        var out = Data()
        writeHeader(&out, .deviceAuth)
        try writeStr(&out, tenantId, "tenant_id")
        writeUuid(&out, challengeId)
        writeUuid(&out, deviceId)
        try writeNonce32(&out, nonce)
        try writeTimeMs(&out, expiresAt)
        return out
    }

    public static func enrollmentBinding(
        tenantId: String,
        enrollmentCode: String,
        platform: Platform,
        deviceKeySpki: Data,
        approvalKeySpki: Data
    ) throws -> Data {
        let normalizedCode = try EnrollmentCodes.normalize(enrollmentCode)

        var out = Data()
        writeHeader(&out, .enrollmentBinding)
        try writeStr(&out, tenantId, "tenant_id")
        try writeStr(&out, normalizedCode, "enrollment_code")
        try writeStr(&out, platform.wire, "platform")
        try writeBlob(&out, deviceKeySpki, "device_key_spki")
        try writeBlob(&out, approvalKeySpki, "approval_key_spki")
        return out
    }

    // MARK: - Encoding primitives

    private static func writeHeader(_ out: inout Data, _ type: PayloadType) {
        out.append(contentsOf: magic)
        out.append(version)
        out.append(type.wire)
    }

    static func writeStr(_ out: inout Data, _ s: String, _ fieldName: String) throws {
        if s.isEmpty {
            throw SigningError.invalidArgument("\(fieldName) cannot be empty")
        }
        if !s.unicodeScalars.elementsEqual(s.precomposedStringWithCanonicalMapping.unicodeScalars) {
            throw SigningError.invalidArgument("\(fieldName) is not NFC-normalized: \(s)")
        }
        for scalar in s.unicodeScalars {
            let cp = scalar.value
            if (cp >= 0x00 && cp <= 0x1F) || (cp >= 0x7F && cp <= 0x9F) {
                throw SigningError.invalidArgument(String(
                    format: "%@ contains control character: U+%04X", fieldName, cp
                ))
            }
        }

        let utf8Bytes = Array(s.utf8)
        if utf8Bytes.isEmpty {
            throw SigningError.invalidArgument("\(fieldName) cannot be empty")
        }
        if utf8Bytes.count > 1024 {
            throw SigningError.invalidArgument(String(
                format: "%@ UTF-8 length exceeds 1024 bytes: %d", fieldName, utf8Bytes.count
            ))
        }

        out.append(UInt8((utf8Bytes.count >> 8) & 0xFF))
        out.append(UInt8(utf8Bytes.count & 0xFF))
        out.append(contentsOf: utf8Bytes)
    }

    static func writeBlob(_ out: inout Data, _ b: Data, _ fieldName: String) throws {
        if b.count > 1024 {
            throw SigningError.invalidArgument(String(
                format: "%@ length exceeds 1024 bytes: %d", fieldName, b.count
            ))
        }
        out.append(UInt8((b.count >> 8) & 0xFF))
        out.append(UInt8(b.count & 0xFF))
        out.append(b)
    }

    static func writeUuid(_ out: inout Data, _ uuid: UUID) {
        let (u0, u1, u2, u3, u4, u5, u6, u7, u8, u9, u10, u11, u12, u13, u14, u15) = uuid.uuid
        out.append(contentsOf: [u0, u1, u2, u3, u4, u5, u6, u7, u8, u9, u10, u11, u12, u13, u14, u15])
    }

    static func writeNonce32(_ out: inout Data, _ nonce: Data) throws {
        guard nonce.count == 32 else {
            throw SigningError.invalidArgument(String(
                format: "Nonce must be exactly 32 bytes, got: %d", nonce.count
            ))
        }
        out.append(nonce)
    }

    static func writeI64(_ out: inout Data, _ value: Int64) {
        let bits = UInt64(bitPattern: value)
        out.append(UInt8((bits >> 56) & 0xFF))
        out.append(UInt8((bits >> 48) & 0xFF))
        out.append(UInt8((bits >> 40) & 0xFF))
        out.append(UInt8((bits >> 32) & 0xFF))
        out.append(UInt8((bits >> 24) & 0xFF))
        out.append(UInt8((bits >> 16) & 0xFF))
        out.append(UInt8((bits >> 8) & 0xFF))
        out.append(UInt8(bits & 0xFF))
    }

    static func writeTimeMs(_ out: inout Data, _ date: Date) throws {
        let millisDouble = (date.timeIntervalSince1970 * 1000.0).rounded()
        guard millisDouble.isFinite,
              millisDouble >= Double(Int64.min),
              millisDouble <= Double(Int64.max) else {
            throw SigningError.invalidArgument("Invalid expiresAt time value: \(date)")
        }
        writeI64(&out, Int64(millisDouble))
    }

    static func writeU8(_ out: inout Data, _ b: UInt8) {
        out.append(b)
    }

    private static func validateAmount(_ amount: Int64) throws {
        if amount <= 0 {
            throw SigningError.invalidArgument("Amount must be positive (> 0), got: \(amount)")
        }
    }

    private static func validateCurrency(_ currency: String) throws {
        let utf8 = Array(currency.utf8)
        guard utf8.count == 3, utf8.allSatisfy({ $0 >= UInt8(ascii: "A") && $0 <= UInt8(ascii: "Z") }) else {
            throw SigningError.invalidArgument(
                "Currency must be an upper-case 3-letter ISO 4217 code, got: \(currency)"
            )
        }
    }
}
