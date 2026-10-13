package io.vepr.contracts.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadEncoderTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    private List<Map<String, Object>> loadVectors() throws IOException {
        Path path = Path.of("../../signing/vectors/v1.json");
        if (!Files.exists(path)) {
            path = Path.of("../signing/vectors/v1.json");
        }
        if (!Files.exists(path)) {
            path = Path.of("signing/vectors/v1.json");
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return Json.parseList(json);
    }

    @Test
    @DisplayName("Property test: mutating ANY single byte in ANY positive payload causes signature verification to fail")
    void testSingleByteMutationCausesVerificationFailure() throws Exception {
        List<Map<String, Object>> vectors = loadVectors();

        for (Map<String, Object> vec : vectors) {
            boolean mustVerify = Boolean.TRUE.equals(vec.get("must_verify"));
            if (!mustVerify || vec.get("payload_hex") == null) {
                continue;
            }

            String name = (String) vec.get("name");
            byte[] originalPayload = HEX.parseHex((String) vec.get("payload_hex"));
            byte[] spkiBytes = B64URL.decode((String) vec.get("public_key_spki_b64url"));
            byte[] signature = B64URL.decode((String) vec.get("signature_der_b64url"));
            ECPublicKey pubKey = SignatureVerifier.parseSpki(spkiBytes);

            // Baseline verification must succeed
            assertThat(SignatureVerifier.verify(pubKey, originalPayload, signature))
                    .as("Baseline verification for '%s' must pass", name)
                    .isTrue();

            // Mutate each single byte of the payload
            for (int i = 0; i < originalPayload.length; i++) {
                byte[] mutated = Arrays.copyOf(originalPayload, originalPayload.length);
                mutated[i] = (byte) (mutated[i] ^ 0x01); // flip lowest bit

                boolean result = SignatureVerifier.verify(pubKey, mutated, signature);
                assertThat(result)
                        .as("Vector '%s': mutating byte at offset %d must fail verification", name, i)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("Input validations for challengeDecision")
    void testChallengeDecisionValidations() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        byte[] nonce32 = new byte[32];
        Instant expires = Instant.now().plusSeconds(60);

        // Empty tenantId
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "", u1, u2, nonce32, "m1", "Merchant", 100, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);

        // Control char in tenantId
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant\u0000bad", u1, u2, nonce32, "m1", "Merchant", 100, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);

        // Non-NFC string in merchantName
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant", u1, u2, nonce32, "m1", "Cafe\u0301", 100, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);

        // Amount <= 0
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant", u1, u2, nonce32, "m1", "Merchant", 0, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant", u1, u2, nonce32, "m1", "Merchant", -5, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid currency
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant", u1, u2, nonce32, "m1", "Merchant", 100, "usd", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant", u1, u2, nonce32, "m1", "Merchant", 100, "US", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid nonce length
        assertThatThrownBy(() -> PayloadEncoder.challengeDecision(
                "tenant", u1, u2, new byte[31], "m1", "Merchant", 100, "USD", Channel.CARD_PRESENT, expires, Decision.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Input validations for preApproval")
    void testPreApprovalValidations() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        byte[] nonce32 = new byte[32];
        Instant expires = Instant.now().plusSeconds(60);

        // Channel other than card_present must be rejected in v1
        assertThatThrownBy(() -> PayloadEncoder.preApproval(
                "tenant", u1, u2, nonce32, 100, "USD", Channel.QR, expires))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card_present");
    }
}
