package io.vepr.contracts.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorTest {

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
        assertThat(path).exists();
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return Json.parseList(json);
    }

    @Test
    @DisplayName("Verify all test vectors in v1.json")
    void testAllVectors() throws Exception {
        List<Map<String, Object>> vectors = loadVectors();
        assertThat(vectors).isNotEmpty();

        for (Map<String, Object> vec : vectors) {
            String name = (String) vec.get("name");
            String type = (String) vec.get("type");
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) vec.get("fields");
            Boolean expectError = (Boolean) vec.get("expect_encoder_error");

            if (Boolean.TRUE.equals(expectError)) {
                // Assert that PayloadEncoder throws for this vector
                assertThatThrownBy(() -> encodeFromFields(type, fields))
                        .as("Vector '%s' must throw IllegalArgumentException", name)
                        .isInstanceOf(IllegalArgumentException.class);
            } else {
                String payloadHex = (String) vec.get("payload_hex");
                String payloadSha256Hex = (String) vec.get("payload_sha256_hex");
                String spkiB64Url = (String) vec.get("public_key_spki_b64url");
                String sigB64Url = (String) vec.get("signature_der_b64url");
                boolean mustVerify = Boolean.TRUE.equals(vec.get("must_verify"));

                byte[] payloadBytes = HEX.parseHex(payloadHex);
                byte[] spkiBytes = B64URL.decode(spkiB64Url);
                byte[] sigBytes = B64URL.decode(sigB64Url);

                // Assert SHA-256 matches
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(payloadBytes);
                assertThat(HEX.formatHex(hash))
                        .as("Vector '%s' SHA-256 mismatch", name)
                        .isEqualTo(payloadSha256Hex);

                // For encodable positive and basic vectors, verify re-encoding matches payload_hex
                if (isDirectlyEncodable(name)) {
                    byte[] encoded = encodeFromFields(type, fields);
                    assertThat(HEX.formatHex(encoded))
                            .as("Vector '%s' re-encoded payload hex mismatch", name)
                            .isEqualTo(payloadHex);
                }

                // Verify signature against public key
                ECPublicKey publicKey = SignatureVerifier.parseSpki(spkiBytes);
                boolean verified = SignatureVerifier.verify(publicKey, payloadBytes, sigBytes);

                assertThat(verified)
                        .as("Vector '%s' verification result mismatch (expected: %s, actual: %s)",
                                name, mustVerify, verified)
                        .isEqualTo(mustVerify);
            }
        }
    }

    private boolean isDirectlyEncodable(String vectorName) {
        return vectorName.startsWith("challenge_decision_approve_")
                || vectorName.equals("challenge_decision_deny_basic")
                || vectorName.equals("pre_approval_basic")
                || vectorName.equals("device_auth_basic")
                || vectorName.equals("enrollment_binding_basic")
                || vectorName.equals("challenge_decision_amount_changed")
                || vectorName.equals("challenge_decision_different_nonce");
    }

    private byte[] encodeFromFields(String type, Map<String, Object> fields) {
        String tenantId = (String) fields.get("tenant_id");

        return switch (type) {
            case "CHALLENGE_DECISION" -> {
                UUID challengeId = fields.get("challenge_id") != null
                        ? UUID.fromString((String) fields.get("challenge_id")) : null;
                UUID deviceId = fields.get("device_id") != null
                        ? UUID.fromString((String) fields.get("device_id")) : null;
                byte[] nonce = fields.get("nonce") != null
                        ? HEX.parseHex((String) fields.get("nonce")) : null;
                String merchantId = (String) fields.get("merchant_id");
                String merchantName = (String) fields.get("merchant_name");
                Number amountMinor = (Number) fields.get("amount_minor");
                String currency = (String) fields.get("currency");
                String channelStr = (String) fields.get("channel");
                Channel channel = channelStr != null ? Channel.fromWire(channelStr) : null;
                Number expiresAtNum = (Number) fields.get("expires_at");
                Instant expiresAt = expiresAtNum != null ? Instant.ofEpochMilli(expiresAtNum.longValue()) : null;
                String decisionStr = (String) fields.get("decision");
                Decision decision = "approve".equalsIgnoreCase(decisionStr)
                        ? Decision.APPROVE : ("deny".equalsIgnoreCase(decisionStr) ? Decision.DENY : null);

                yield PayloadEncoder.challengeDecision(
                        tenantId, challengeId, deviceId, nonce,
                        merchantId, merchantName,
                        amountMinor != null ? amountMinor.longValue() : 0L,
                        currency, channel, expiresAt, decision);
            }
            case "PRE_APPROVAL" -> {
                UUID preApprovalId = UUID.fromString((String) fields.get("pre_approval_id"));
                UUID deviceId = UUID.fromString((String) fields.get("device_id"));
                byte[] nonce = HEX.parseHex((String) fields.get("nonce"));
                Number maxAmount = (Number) fields.get("max_amount_minor");
                String currency = (String) fields.get("currency");
                Channel channelScope = Channel.fromWire((String) fields.get("channel_scope"));
                Instant expiresAt = Instant.ofEpochMilli(((Number) fields.get("expires_at")).longValue());

                yield PayloadEncoder.preApproval(
                        tenantId, preApprovalId, deviceId, nonce,
                        maxAmount.longValue(), currency, channelScope, expiresAt);
            }
            case "DEVICE_AUTH" -> {
                UUID challengeId = UUID.fromString((String) fields.get("challenge_id"));
                UUID deviceId = UUID.fromString((String) fields.get("device_id"));
                byte[] nonce = HEX.parseHex((String) fields.get("nonce"));
                Instant expiresAt = Instant.ofEpochMilli(((Number) fields.get("expires_at")).longValue());

                yield PayloadEncoder.deviceAuth(tenantId, challengeId, deviceId, nonce, expiresAt);
            }
            case "ENROLLMENT_BINDING" -> {
                String enrollmentCode = (String) fields.get("enrollment_code");
                Platform platform = Platform.fromWire((String) fields.get("platform"));
                byte[] deviceKeySpki = B64URL.decode((String) fields.get("device_key_spki_b64url"));
                byte[] approvalKeySpki = B64URL.decode((String) fields.get("approval_key_spki_b64url"));

                yield PayloadEncoder.enrollmentBinding(
                        tenantId, enrollmentCode, platform, deviceKeySpki, approvalKeySpki);
            }
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }
}
