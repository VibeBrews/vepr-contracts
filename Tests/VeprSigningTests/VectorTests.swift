import CryptoKit
import Foundation
import Testing
@testable import VeprSigning

@Suite("Canonical Signing Vector Tests")
struct VectorTests {

    static let vectors: [Vector] = loadVectors()

    @Test("Test vector", arguments: vectors)
    func testVector(_ vector: Vector) throws {
        if vector.expectEncoderError == true {
            #expect(throws: SigningError.self) {
                _ = try encodeFromFields(type: vector.type, fields: vector.fields)
            }
            return
        }

        let payloadHex = try #require(vector.payloadHex)
        let payloadSha256Hex = try #require(vector.payloadSha256Hex)
        let spkiB64Url = try #require(vector.publicKeySpkiB64url)
        let sigB64Url = try #require(vector.signatureDerB64url)
        let mustVerify = try #require(vector.mustVerify)

        let payloadBytes = try #require(Data.fromHex(payloadHex))
        let spkiBytes = try #require(Data.fromBase64Url(spkiB64Url))
        let sigBytes = try #require(Data.fromBase64Url(sigB64Url))

        // Assert SHA-256 matches
        let hash = SHA256.hash(data: payloadBytes)
        let hashHex = hash.map { String(format: "%02x", $0) }.joined()
        #expect(hashHex == payloadSha256Hex)

        // For directly encodable vectors, verify re-encoded hex equals payload_hex
        if isDirectlyEncodable(vector.name) {
            let encoded = try encodeFromFields(type: vector.type, fields: vector.fields)
            #expect(encoded.hexEncodedString == payloadHex)
        }

        // Verify signature: DER parse failure counts as not verified (e.g., raw_rs_signature)
        var verified = false
        if let publicKey = try? P256.Signing.PublicKey(derRepresentation: spkiBytes),
           let signature = try? P256.Signing.ECDSASignature(derRepresentation: sigBytes) {
            verified = publicKey.isValidSignature(signature, for: payloadBytes)
        }
        #expect(verified == mustVerify)
    }

    private func isDirectlyEncodable(_ vectorName: String) -> Bool {
        vectorName.hasPrefix("challenge_decision_approve_")
            || vectorName == "challenge_decision_deny_basic"
            || vectorName == "pre_approval_basic"
            || vectorName == "device_auth_basic"
            || vectorName == "enrollment_binding_basic"
            || vectorName == "challenge_decision_amount_changed"
            || vectorName == "challenge_decision_different_nonce"
    }

    private func encodeFromFields(type: String, fields: VectorFields) throws -> Data {
        let tenantId = try #require(fields.tenantId)

        switch type {
        case "CHALLENGE_DECISION":
            let challengeId = try #require(fields.challengeId.flatMap(UUID.init(uuidString:)))
            let deviceId = try #require(fields.deviceId.flatMap(UUID.init(uuidString:)))
            let nonce = try #require(fields.nonce.flatMap(Data.fromHex))
            let merchantId = try #require(fields.merchantId)
            let merchantName = try #require(fields.merchantName)
            let amountMinor = try #require(fields.amountMinor)
            let currency = try #require(fields.currency)
            let channelStr = try #require(fields.channel)
            let channel = try Channel.fromWire(channelStr)
            let expiresAtMillis = try #require(fields.expiresAt)
            let expiresAt = Date(timeIntervalSince1970: Double(expiresAtMillis) / 1000.0)
            let decisionStr = try #require(fields.decision)
            let decision: Decision
            if decisionStr.caseInsensitiveCompare("approve") == .orderedSame {
                decision = .approve
            } else if decisionStr.caseInsensitiveCompare("deny") == .orderedSame {
                decision = .deny
            } else {
                throw SigningError.invalidArgument("Unknown decision string: \(decisionStr)")
            }

            return try PayloadEncoder.challengeDecision(
                tenantId: tenantId,
                challengeId: challengeId,
                deviceId: deviceId,
                nonce: nonce,
                merchantId: merchantId,
                merchantName: merchantName,
                amountMinor: amountMinor,
                currency: currency,
                channel: channel,
                expiresAt: expiresAt,
                decision: decision
            )

        case "PRE_APPROVAL":
            let preApprovalId = try #require(fields.preApprovalId.flatMap(UUID.init(uuidString:)))
            let deviceId = try #require(fields.deviceId.flatMap(UUID.init(uuidString:)))
            let nonce = try #require(fields.nonce.flatMap(Data.fromHex))
            let maxAmount = try #require(fields.maxAmountMinor)
            let currency = try #require(fields.currency)
            let channelScopeStr = try #require(fields.channelScope)
            let channelScope = try Channel.fromWire(channelScopeStr)
            let expiresAtMillis = try #require(fields.expiresAt)
            let expiresAt = Date(timeIntervalSince1970: Double(expiresAtMillis) / 1000.0)

            return try PayloadEncoder.preApproval(
                tenantId: tenantId,
                preApprovalId: preApprovalId,
                deviceId: deviceId,
                nonce: nonce,
                maxAmountMinor: maxAmount,
                currency: currency,
                channelScope: channelScope,
                expiresAt: expiresAt
            )

        case "DEVICE_AUTH":
            let challengeId = try #require(fields.challengeId.flatMap(UUID.init(uuidString:)))
            let deviceId = try #require(fields.deviceId.flatMap(UUID.init(uuidString:)))
            let nonce = try #require(fields.nonce.flatMap(Data.fromHex))
            let expiresAtMillis = try #require(fields.expiresAt)
            let expiresAt = Date(timeIntervalSince1970: Double(expiresAtMillis) / 1000.0)

            return try PayloadEncoder.deviceAuth(
                tenantId: tenantId,
                challengeId: challengeId,
                deviceId: deviceId,
                nonce: nonce,
                expiresAt: expiresAt
            )

        case "ENROLLMENT_BINDING":
            let enrollmentCode = try #require(fields.enrollmentCode)
            let platformStr = try #require(fields.platform)
            let platform = try Platform.fromWire(platformStr)
            let deviceKeySpki = try #require(fields.deviceKeySpkiB64url.flatMap(Data.fromBase64Url))
            let approvalKeySpki = try #require(fields.approvalKeySpkiB64url.flatMap(Data.fromBase64Url))

            return try PayloadEncoder.enrollmentBinding(
                tenantId: tenantId,
                enrollmentCode: enrollmentCode,
                platform: platform,
                deviceKeySpki: deviceKeySpki,
                approvalKeySpki: approvalKeySpki
            )

        default:
            throw SigningError.invalidArgument("Unknown payload type: \(type)")
        }
    }

    private static func loadVectors() -> [Vector] {
        let data: Data
        if let url = Bundle.module.url(forResource: "v1", withExtension: "json", subdirectory: "Resources") {
            data = (try? Data(contentsOf: url)) ?? loadFallback()
        } else if let url = Bundle.module.url(forResource: "v1", withExtension: "json") {
            data = (try? Data(contentsOf: url)) ?? loadFallback()
        } else {
            data = loadFallback()
        }

        do {
            return try JSONDecoder().decode([Vector].self, from: data)
        } catch {
            fatalError("Failed to decode v1.json: \(error)")
        }
    }

    private static func loadFallback() -> Data {
        let currentDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let localUrl = currentDir.appendingPathComponent("Resources/v1.json")
        if let localData = try? Data(contentsOf: localUrl) {
            return localData
        }
        fatalError("Could not locate v1.json in Bundle.module or Resources/v1.json")
    }
}

// MARK: - Models

struct Vector: Decodable, Sendable, CustomTestStringConvertible {
    let name: String
    let type: String
    let fields: VectorFields
    let payloadHex: String?
    let payloadSha256Hex: String?
    let publicKeySpkiB64url: String?
    let signatureDerB64url: String?
    let mustVerify: Bool?
    let expectEncoderError: Bool?

    var testDescription: String {
        name
    }

    enum CodingKeys: String, CodingKey {
        case name
        case type
        case fields
        case payloadHex = "payload_hex"
        case payloadSha256Hex = "payload_sha256_hex"
        case publicKeySpkiB64url = "public_key_spki_b64url"
        case signatureDerB64url = "signature_der_b64url"
        case mustVerify = "must_verify"
        case expectEncoderError = "expect_encoder_error"
    }
}

struct VectorFields: Decodable, Sendable {
    let tenantId: String?
    let challengeId: String?
    let deviceId: String?
    let preApprovalId: String?
    let nonce: String?
    let merchantId: String?
    let merchantName: String?
    let amountMinor: Int64?
    let maxAmountMinor: Int64?
    let currency: String?
    let channel: String?
    let channelScope: String?
    let expiresAt: Int64?
    let decision: String?
    let enrollmentCode: String?
    let platform: String?
    let deviceKeySpkiB64url: String?
    let approvalKeySpkiB64url: String?

    enum CodingKeys: String, CodingKey {
        case tenantId = "tenant_id"
        case challengeId = "challenge_id"
        case deviceId = "device_id"
        case preApprovalId = "pre_approval_id"
        case nonce
        case merchantId = "merchant_id"
        case merchantName = "merchant_name"
        case amountMinor = "amount_minor"
        case maxAmountMinor = "max_amount_minor"
        case currency
        case channel
        case channelScope = "channel_scope"
        case expiresAt = "expires_at"
        case decision
        case enrollmentCode = "enrollment_code"
        case platform
        case deviceKeySpkiB64url = "device_key_spki_b64url"
        case approvalKeySpkiB64url = "approval_key_spki_b64url"
    }
}

// MARK: - Data Helpers

extension Data {
    static func fromHex(_ hex: String) -> Data? {
        if hex.count % 2 != 0 {
            return nil
        }
        var data = Data()
        data.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<nextIndex], radix: 16) else {
                return nil
            }
            data.append(byte)
            index = nextIndex
        }
        return data
    }

    var hexEncodedString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    static func fromBase64Url(_ string: String) -> Data? {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 {
            base64.append("=")
        }
        return Data(base64Encoded: base64)
    }
}
