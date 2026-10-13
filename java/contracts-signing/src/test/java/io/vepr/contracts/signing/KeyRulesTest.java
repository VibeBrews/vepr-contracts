package io.vepr.contracts.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRulesTest {

    private final UUID u1 = UUID.randomUUID();
    private final UUID u2 = UUID.randomUUID();
    private final byte[] nonce = new byte[32];
    private final Instant expires = Instant.now().plusSeconds(60);

    @Test
    @DisplayName("Verify SPEC §5 permission matrix for Approval and Device keys")
    void testPermissionMatrix() {
        byte[] approvePayload = PayloadEncoder.challengeDecision(
                "t", u1, u2, nonce, "m", "M", 100, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE);
        byte[] denyPayload = PayloadEncoder.challengeDecision(
                "t", u1, u2, nonce, "m", "M", 100, "USD", Channel.CARD_PRESENT, expires, Decision.DENY);
        byte[] preApprovalPayload = PayloadEncoder.preApproval(
                "t", u1, u2, nonce, 100, "USD", Channel.CARD_PRESENT, expires);
        byte[] deviceAuthPayload = PayloadEncoder.deviceAuth(
                "t", u1, u2, nonce, expires);
        byte[] enrollmentPayload = PayloadEncoder.enrollmentBinding(
                "t", "CODE123", Platform.IOS, new byte[10], new byte[10]);

        // 1. Approval key (Face ID):
        // May sign: CHALLENGE_DECISION with 0x01; PRE_APPROVAL
        // Must never sign: DEVICE_AUTH; CHALLENGE_DECISION with 0x02; ENROLLMENT_BINDING
        assertThat(KeyRules.isAllowed(approvePayload, KeyType.APPROVAL)).isTrue();
        assertThat(KeyRules.isAllowed(preApprovalPayload, KeyType.APPROVAL)).isTrue();
        assertThat(KeyRules.isAllowed(denyPayload, KeyType.APPROVAL)).isFalse();
        assertThat(KeyRules.isAllowed(deviceAuthPayload, KeyType.APPROVAL)).isFalse();
        assertThat(KeyRules.isAllowed(enrollmentPayload, KeyType.APPROVAL)).isFalse();

        // 2. Device key (no biometric):
        // May sign: DEVICE_AUTH; CHALLENGE_DECISION with 0x02
        // Must never sign: CHALLENGE_DECISION with 0x01; PRE_APPROVAL; ENROLLMENT_BINDING
        assertThat(KeyRules.isAllowed(denyPayload, KeyType.DEVICE)).isTrue();
        assertThat(KeyRules.isAllowed(deviceAuthPayload, KeyType.DEVICE)).isTrue();
        assertThat(KeyRules.isAllowed(approvePayload, KeyType.DEVICE)).isFalse();
        assertThat(KeyRules.isAllowed(preApprovalPayload, KeyType.DEVICE)).isFalse();
        assertThat(KeyRules.isAllowed(enrollmentPayload, KeyType.DEVICE)).isFalse();
    }

    @Test
    @DisplayName("KeyRules header parsing rejects invalid magic, version, type, or short payloads")
    void testHeaderValidation() {
        // Short payload
        assertThatThrownBy(() -> KeyRules.isAllowed(new byte[]{ 'V', 'E', 'P', 'R', 0x01 }, KeyType.APPROVAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than required 6-byte header");

        // Bad magic
        byte[] badMagic = new byte[]{ 'B', 'A', 'D', '!', 0x01, 0x02 };
        assertThatThrownBy(() -> KeyRules.isAllowed(badMagic, KeyType.APPROVAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid magic byte");

        // Bad version
        byte[] badVersion = new byte[]{ 'V', 'E', 'P', 'R', 0x02, 0x02 };
        assertThatThrownBy(() -> KeyRules.isAllowed(badVersion, KeyType.APPROVAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported payload version");

        // Unknown type
        byte[] badType = new byte[]{ 'V', 'E', 'P', 'R', 0x01, 0x05 };
        assertThatThrownBy(() -> KeyRules.isAllowed(badType, KeyType.APPROVAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown payload type byte");

        // Type 0x01 with invalid trailing decision byte
        byte[] badDecision = new byte[]{ 'V', 'E', 'P', 'R', 0x01, 0x01, 0x03 };
        assertThatThrownBy(() -> KeyRules.isAllowed(badDecision, KeyType.APPROVAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown decision byte");
    }

    @Test
    @DisplayName("KeyRules does not parse interior payload bytes (only header and type 0x01 trailing decision)")
    void testDoesNotParseInterior() {
        // Type 0x02 (PRE_APPROVAL): 6 bytes header + arbitrary garbage bytes
        byte[] dummyPreApproval = new byte[]{ 'V', 'E', 'P', 'R', 0x01, 0x02, (byte) 0xFF, (byte) 0xEE };
        assertThat(KeyRules.isAllowed(dummyPreApproval, KeyType.APPROVAL)).isTrue();
        assertThat(KeyRules.isAllowed(dummyPreApproval, KeyType.DEVICE)).isFalse();

        // Type 0x03 (DEVICE_AUTH): 6 bytes header + arbitrary garbage bytes
        byte[] dummyDeviceAuth = new byte[]{ 'V', 'E', 'P', 'R', 0x01, 0x03, (byte) 0xAA, (byte) 0xBB };
        assertThat(KeyRules.isAllowed(dummyDeviceAuth, KeyType.APPROVAL)).isFalse();
        assertThat(KeyRules.isAllowed(dummyDeviceAuth, KeyType.DEVICE)).isTrue();
    }
}
