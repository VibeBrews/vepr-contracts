import Foundation
import Testing
@testable import VeprSigning

@Suite("KeyRules Tests")
struct KeyRulesTests {

    private let u1 = UUID()
    private let u2 = UUID()
    private let nonce = Data(repeating: 0, count: 32)
    private let expires = Date().addingTimeInterval(60)

    @Test("SPEC §5 permission matrix for Approval and Device keys")
    func permissionMatrix() throws {
        let approvePayload = try PayloadEncoder.challengeDecision(
            tenantId: "t",
            challengeId: u1,
            deviceId: u2,
            nonce: nonce,
            merchantId: "m",
            merchantName: "M",
            amountMinor: 100,
            currency: "USD",
            channel: .cardPresent,
            expiresAt: expires,
            decision: .approve
        )
        let denyPayload = try PayloadEncoder.challengeDecision(
            tenantId: "t",
            challengeId: u1,
            deviceId: u2,
            nonce: nonce,
            merchantId: "m",
            merchantName: "M",
            amountMinor: 100,
            currency: "USD",
            channel: .cardPresent,
            expiresAt: expires,
            decision: .deny
        )
        let preApprovalPayload = try PayloadEncoder.preApproval(
            tenantId: "t",
            preApprovalId: u1,
            deviceId: u2,
            nonce: nonce,
            maxAmountMinor: 100,
            currency: "USD",
            channelScope: .cardPresent,
            expiresAt: expires
        )
        let deviceAuthPayload = try PayloadEncoder.deviceAuth(
            tenantId: "t",
            challengeId: u1,
            deviceId: u2,
            nonce: nonce,
            expiresAt: expires
        )
        let enrollmentPayload = try PayloadEncoder.enrollmentBinding(
            tenantId: "t",
            enrollmentCode: "CODE123",
            platform: .ios,
            deviceKeySpki: Data(repeating: 0, count: 10),
            approvalKeySpki: Data(repeating: 0, count: 10)
        )

        // 1. Approval key (Face ID):
        // May sign: CHALLENGE_DECISION with 0x01; PRE_APPROVAL
        // Must never sign: DEVICE_AUTH; CHALLENGE_DECISION with 0x02; ENROLLMENT_BINDING
        #expect(try KeyRules.isAllowed(approvePayload, keyType: .approval) == true)
        #expect(try KeyRules.isAllowed(preApprovalPayload, keyType: .approval) == true)
        #expect(try KeyRules.isAllowed(denyPayload, keyType: .approval) == false)
        #expect(try KeyRules.isAllowed(deviceAuthPayload, keyType: .approval) == false)
        #expect(try KeyRules.isAllowed(enrollmentPayload, keyType: .approval) == false)

        // 2. Device key (no biometric):
        // May sign: DEVICE_AUTH; CHALLENGE_DECISION with 0x02
        // Must never sign: CHALLENGE_DECISION with 0x01; PRE_APPROVAL; ENROLLMENT_BINDING
        #expect(try KeyRules.isAllowed(denyPayload, keyType: .device) == true)
        #expect(try KeyRules.isAllowed(deviceAuthPayload, keyType: .device) == true)
        #expect(try KeyRules.isAllowed(approvePayload, keyType: .device) == false)
        #expect(try KeyRules.isAllowed(preApprovalPayload, keyType: .device) == false)
        #expect(try KeyRules.isAllowed(enrollmentPayload, keyType: .device) == false)
    }

    @Test("Header validation rejects invalid magic, version, type, or short payloads")
    func headerValidation() {
        // Short payload
        #expect(throws: SigningError.self) {
            try KeyRules.isAllowed(Data([0x56, 0x45, 0x50, 0x52, 0x01]), keyType: .approval)
        }

        // Bad magic
        let badMagic = Data([0x42, 0x41, 0x44, 0x21, 0x01, 0x02])
        #expect(throws: SigningError.self) {
            try KeyRules.isAllowed(badMagic, keyType: .approval)
        }

        // Bad version
        let badVersion = Data([0x56, 0x45, 0x50, 0x52, 0x02, 0x02])
        #expect(throws: SigningError.self) {
            try KeyRules.isAllowed(badVersion, keyType: .approval)
        }

        // Unknown type
        let badType = Data([0x56, 0x45, 0x50, 0x52, 0x01, 0x05])
        #expect(throws: SigningError.self) {
            try KeyRules.isAllowed(badType, keyType: .approval)
        }

        // Type 0x01 with invalid trailing decision byte
        let badDecision = Data([0x56, 0x45, 0x50, 0x52, 0x01, 0x01, 0x03])
        #expect(throws: SigningError.self) {
            try KeyRules.isAllowed(badDecision, keyType: .approval)
        }

        // Type 0x01 too short for decision byte
        let tooShortDecision = Data([0x56, 0x45, 0x50, 0x52, 0x01, 0x01])
        #expect(throws: SigningError.self) {
            try KeyRules.isAllowed(tooShortDecision, keyType: .approval)
        }
    }

    @Test("KeyRules does not parse interior payload bytes (only header and type 0x01 trailing decision)")
    func doesNotParseInterior() throws {
        // Type 0x02 (PRE_APPROVAL): 6 bytes header + arbitrary garbage bytes
        let dummyPreApproval = Data([0x56, 0x45, 0x50, 0x52, 0x01, 0x02, 0xFF, 0xEE])
        #expect(try KeyRules.isAllowed(dummyPreApproval, keyType: .approval) == true)
        #expect(try KeyRules.isAllowed(dummyPreApproval, keyType: .device) == false)

        // Type 0x03 (DEVICE_AUTH): 6 bytes header + arbitrary garbage bytes
        let dummyDeviceAuth = Data([0x56, 0x45, 0x50, 0x52, 0x01, 0x03, 0xAA, 0xBB])
        #expect(try KeyRules.isAllowed(dummyDeviceAuth, keyType: .approval) == false)
        #expect(try KeyRules.isAllowed(dummyDeviceAuth, keyType: .device) == true)
    }
}
