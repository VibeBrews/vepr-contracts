package io.vepr.contracts.signing;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.nist.NISTNamedCurves;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VectorGenerator {

    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        boolean verifyMode = false;
        String dirArg = null;

        if (args.length > 0 && "--verify".equals(args[0])) {
            verifyMode = true;
            if (args.length > 1) {
                dirArg = args[1];
            }
        } else if (args.length > 0) {
            dirArg = args[0];
        }

        if (dirArg == null) {
            dirArg = "signing/vectors";
        }

        Path vectorsDir = Path.of(dirArg).toAbsolutePath().normalize();
        Path primaryKeyPath = vectorsDir.resolve("TEST-ONLY-p256.pem");
        Path secondaryKeyPath = vectorsDir.resolve("TEST-ONLY-p256-other.pem");
        Path v1JsonPath = vectorsDir.resolve("v1.json");

        if (!Files.exists(primaryKeyPath) || !Files.exists(secondaryKeyPath)) {
            System.err.println("Test keys not found in: " + vectorsDir);
            System.exit(1);
        }

        KeyBundle primary = loadKey(primaryKeyPath);
        KeyBundle secondary = loadKey(secondaryKeyPath);

        List<Map<String, Object>> vectors = generateAllVectors(primary, secondary);
        String json = Json.toPrettyJson(vectors);

        if (verifyMode) {
            if (!Files.exists(v1JsonPath)) {
                System.err.println("Missing committed file: " + v1JsonPath);
                System.exit(1);
            }
            String committed = Files.readString(v1JsonPath, StandardCharsets.UTF_8);
            if (!committed.equals(json)) {
                System.err.println("Committed signing/vectors/v1.json is out of date! Run './gradlew generateVectors' and commit.");
                System.exit(1);
            }
            System.out.println("Vectors in " + v1JsonPath + " are up-to-date.");
        } else {
            Files.writeString(v1JsonPath, json, StandardCharsets.UTF_8);
            System.out.println("Successfully generated " + vectors.size() + " test vectors in: " + v1JsonPath);
        }
    }

    record KeyBundle(ECPrivateKeyParameters privateKey, byte[] spkiDer, String spkiB64Url) {}

    static KeyBundle loadKey(Path pemPath) throws IOException {
        String content = Files.readString(pemPath, StandardCharsets.UTF_8);
        StringBuilder cleanPem = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (!line.trim().startsWith("#")) {
                cleanPem.append(line).append("\n");
            }
        }
        try (PEMParser parser = new PEMParser(new StringReader(cleanPem.toString()))) {
            Object obj = parser.readObject();
            if (!(obj instanceof PEMKeyPair kp)) {
                throw new IllegalArgumentException("Expected PEMKeyPair from " + pemPath + ", got: " + (obj == null ? "null" : obj.getClass()));
            }
            PrivateKeyInfo privInfo = kp.getPrivateKeyInfo();
            SubjectPublicKeyInfo pubInfo = kp.getPublicKeyInfo();
            ECPrivateKeyParameters privKeyParams = (ECPrivateKeyParameters) PrivateKeyFactory.createKey(privInfo);
            byte[] spkiDer = pubInfo.getEncoded(ASN1Encoding.DER);
            String spkiB64Url = B64URL.encodeToString(spkiDer);
            return new KeyBundle(privKeyParams, spkiDer, spkiB64Url);
        }
    }

    public static List<Map<String, Object>> generateAllVectors(KeyBundle primary, KeyBundle secondary) {
        List<Map<String, Object>> list = new ArrayList<>();

        // Standard constants
        String tenantId = "tenant_test_123";
        UUID challengeId1 = UUID.fromString("018f0000-0000-7000-8000-000000000001");
        UUID deviceId = UUID.fromString("018f0000-0000-7000-8000-000000000002");
        UUID preApprovalId = UUID.fromString("018f0000-0000-7000-8000-000000000003");
        UUID challengeId2 = UUID.fromString("018f0000-0000-7000-8000-000000000004");
        byte[] nonce32 = HEX.parseHex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
        String merchantId = "merch_123";
        String merchantName = "Coffee Shop";
        long amount1500 = 1500L;
        String currencyInr = "INR";
        Channel channelCardPresent = Channel.CARD_PRESENT;
        Instant expiresAt = Instant.ofEpochMilli(1770000000000L);

        // 1. challenge_decision_approve_basic
        byte[] payload1 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, nonce32, merchantId, merchantName,
                amount1500, currencyInr, channelCardPresent, expiresAt, Decision.APPROVE);
        byte[] sig1 = signDeterministic(primary.privateKey, payload1);
        Map<String, Object> fields1 = new LinkedHashMap<>();
        fields1.put("tenant_id", tenantId);
        fields1.put("challenge_id", challengeId1.toString());
        fields1.put("device_id", deviceId.toString());
        fields1.put("nonce", HEX.formatHex(nonce32));
        fields1.put("merchant_id", merchantId);
        fields1.put("merchant_name", merchantName);
        fields1.put("amount_minor", amount1500);
        fields1.put("currency", currencyInr);
        fields1.put("channel", channelCardPresent.wire());
        fields1.put("expires_at", expiresAt.toEpochMilli());
        fields1.put("decision", "approve");
        list.add(createVector("challenge_decision_approve_basic", "CHALLENGE_DECISION", fields1, payload1, primary, sig1, true));

        // 2. challenge_decision_deny_basic
        byte[] payload2 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, nonce32, merchantId, merchantName,
                amount1500, currencyInr, channelCardPresent, expiresAt, Decision.DENY);
        byte[] sig2 = signDeterministic(primary.privateKey, payload2);
        Map<String, Object> fields2 = new LinkedHashMap<>(fields1);
        fields2.put("decision", "deny");
        list.add(createVector("challenge_decision_deny_basic", "CHALLENGE_DECISION", fields2, payload2, primary, sig2, true));

        // 3. pre_approval_basic
        byte[] payload3 = PayloadEncoder.preApproval(
                tenantId, preApprovalId, deviceId, nonce32, 5000L, currencyInr, channelCardPresent, expiresAt);
        byte[] sig3 = signDeterministic(primary.privateKey, payload3);
        Map<String, Object> fields3 = new LinkedHashMap<>();
        fields3.put("tenant_id", tenantId);
        fields3.put("pre_approval_id", preApprovalId.toString());
        fields3.put("device_id", deviceId.toString());
        fields3.put("nonce", HEX.formatHex(nonce32));
        fields3.put("max_amount_minor", 5000L);
        fields3.put("currency", currencyInr);
        fields3.put("channel_scope", channelCardPresent.wire());
        fields3.put("expires_at", expiresAt.toEpochMilli());
        list.add(createVector("pre_approval_basic", "PRE_APPROVAL", fields3, payload3, primary, sig3, true));

        // 4. device_auth_basic
        byte[] payload4 = PayloadEncoder.deviceAuth(tenantId, challengeId2, deviceId, nonce32, expiresAt);
        byte[] sig4 = signDeterministic(primary.privateKey, payload4);
        Map<String, Object> fields4 = new LinkedHashMap<>();
        fields4.put("tenant_id", tenantId);
        fields4.put("challenge_id", challengeId2.toString());
        fields4.put("device_id", deviceId.toString());
        fields4.put("nonce", HEX.formatHex(nonce32));
        fields4.put("expires_at", expiresAt.toEpochMilli());
        list.add(createVector("device_auth_basic", "DEVICE_AUTH", fields4, payload4, primary, sig4, true));

        // 5. enrollment_binding_basic
        String rawEnrollmentCode = "CODE-1234-OL";
        byte[] payload5 = PayloadEncoder.enrollmentBinding(
                tenantId, rawEnrollmentCode, Platform.IOS, primary.spkiDer, secondary.spkiDer);
        byte[] sig5 = signDeterministic(primary.privateKey, payload5);
        Map<String, Object> fields5 = new LinkedHashMap<>();
        fields5.put("tenant_id", tenantId);
        fields5.put("enrollment_code", rawEnrollmentCode);
        fields5.put("platform", "ios");
        fields5.put("device_key_spki_b64url", primary.spkiB64Url);
        fields5.put("approval_key_spki_b64url", secondary.spkiB64Url);
        list.add(createVector("enrollment_binding_basic", "ENROLLMENT_BINDING", fields5, payload5, primary, sig5, true));

        // Edge cases that must verify:
        // 6. challenge_decision_approve_multibyte_utf8 (e.g. चाय वाला ☕)
        String multiByteName = "चाय वाला ☕";
        byte[] payload6 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, nonce32, merchantId, multiByteName,
                amount1500, currencyInr, channelCardPresent, expiresAt, Decision.APPROVE);
        byte[] sig6 = signDeterministic(primary.privateKey, payload6);
        Map<String, Object> fields6 = new LinkedHashMap<>(fields1);
        fields6.put("merchant_name", multiByteName);
        list.add(createVector("challenge_decision_approve_multibyte_utf8", "CHALLENGE_DECISION", fields6, payload6, primary, sig6, true));

        // 7. challenge_decision_approve_amount_one (amount = 1)
        byte[] payload7 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, nonce32, merchantId, merchantName,
                1L, currencyInr, channelCardPresent, expiresAt, Decision.APPROVE);
        byte[] sig7 = signDeterministic(primary.privateKey, payload7);
        Map<String, Object> fields7 = new LinkedHashMap<>(fields1);
        fields7.put("amount_minor", 1L);
        list.add(createVector("challenge_decision_approve_amount_one", "CHALLENGE_DECISION", fields7, payload7, primary, sig7, true));

        // 8. challenge_decision_approve_amount_large (amount = 9000000000000000)
        long largeAmount = 9000000000000000L;
        byte[] payload8 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, nonce32, merchantId, merchantName,
                largeAmount, currencyInr, channelCardPresent, expiresAt, Decision.APPROVE);
        byte[] sig8 = signDeterministic(primary.privateKey, payload8);
        Map<String, Object> fields8 = new LinkedHashMap<>(fields1);
        fields8.put("amount_minor", largeAmount);
        list.add(createVector("challenge_decision_approve_amount_large", "CHALLENGE_DECISION", fields8, payload8, primary, sig8, true));

        // Verification failures (must_verify: false):
        // 9. challenge_decision_amount_changed: amount changed by 1, signature from basic
        byte[] payload9 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, nonce32, merchantId, merchantName,
                amount1500 + 1, currencyInr, channelCardPresent, expiresAt, Decision.APPROVE);
        Map<String, Object> fields9 = new LinkedHashMap<>(fields1);
        fields9.put("amount_minor", amount1500 + 1);
        list.add(createVector("challenge_decision_amount_changed", "CHALLENGE_DECISION", fields9, payload9, primary, sig1, false));

        // 10. challenge_decision_different_nonce: different nonce, signature from basic
        byte[] differentNonce = Arrays.copyOf(nonce32, nonce32.length);
        differentNonce[0] ^= 0xFF;
        byte[] payload10 = PayloadEncoder.challengeDecision(
                tenantId, challengeId1, deviceId, differentNonce, merchantId, merchantName,
                amount1500, currencyInr, channelCardPresent, expiresAt, Decision.APPROVE);
        Map<String, Object> fields10 = new LinkedHashMap<>(fields1);
        fields10.put("nonce", HEX.formatHex(differentNonce));
        list.add(createVector("challenge_decision_different_nonce", "CHALLENGE_DECISION", fields10, payload10, primary, sig1, false));

        // 11. challenge_decision_decision_flipped: decision byte flipped in payload, signature from basic
        byte[] payload11 = Arrays.copyOf(payload1, payload1.length);
        payload11[payload11.length - 1] = 0x02; // DENY
        Map<String, Object> fields11 = new LinkedHashMap<>(fields1);
        fields11.put("decision", "deny");
        list.add(createVector("challenge_decision_decision_flipped", "CHALLENGE_DECISION", fields11, payload11, primary, sig1, false));

        // 12. challenge_decision_type_byte_changed: type byte at offset 5 changed from 0x01 to 0x02
        byte[] payload12 = Arrays.copyOf(payload1, payload1.length);
        payload12[5] = 0x02;
        list.add(createVector("challenge_decision_type_byte_changed", "CHALLENGE_DECISION", fields1, payload12, primary, sig1, false));

        // 13. challenge_decision_version_02: version byte at offset 4 changed from 0x01 to 0x02
        byte[] payload13 = Arrays.copyOf(payload1, payload1.length);
        payload13[4] = 0x02;
        list.add(createVector("challenge_decision_version_02", "CHALLENGE_DECISION", fields1, payload13, primary, sig1, false));

        // 14. challenge_decision_trailing_byte: trailing byte appended
        byte[] payload14 = Arrays.copyOf(payload1, payload1.length + 1);
        payload14[payload14.length - 1] = 0x00;
        list.add(createVector("challenge_decision_trailing_byte", "CHALLENGE_DECISION", fields1, payload14, primary, sig1, false));

        // 15. challenge_decision_raw_rs_signature: valid payload, raw r||s 64-byte signature
        byte[] rawRsSig = derToRawRs(sig1);
        list.add(createVector("challenge_decision_raw_rs_signature", "CHALLENGE_DECISION", fields1, payload1, primary, rawRsSig, false));

        // 16. challenge_decision_wrong_key: valid payload signed by secondary key, verified against primary SPKI
        byte[] sig16 = signDeterministic(secondary.privateKey, payload1);
        list.add(createVector("challenge_decision_wrong_key", "CHALLENGE_DECISION", fields1, payload1, primary, sig16, false));

        // Encoder rejections (expect_encoder_error: true):
        // 17. encoder_error_non_nfc_merchant_name
        Map<String, Object> fields17 = new LinkedHashMap<>(fields1);
        fields17.put("merchant_name", "Cafe\u0301"); // NFD 'e' + combining acute
        list.add(createEncoderErrorVector("encoder_error_non_nfc_merchant_name", "CHALLENGE_DECISION", fields17, "merchant_name is not NFC-normalized"));

        // 18. encoder_error_empty_tenant_id
        Map<String, Object> fields18 = new LinkedHashMap<>(fields1);
        fields18.put("tenant_id", "");
        list.add(createEncoderErrorVector("encoder_error_empty_tenant_id", "CHALLENGE_DECISION", fields18, "tenant_id cannot be empty"));

        // 19. encoder_error_string_over_1024_bytes
        Map<String, Object> fields19 = new LinkedHashMap<>(fields1);
        fields19.put("merchant_name", "A".repeat(1025));
        list.add(createEncoderErrorVector("encoder_error_string_over_1024_bytes", "CHALLENGE_DECISION", fields19, "merchant_name exceeds 1024 bytes"));

        // 20. encoder_error_control_character
        Map<String, Object> fields20 = new LinkedHashMap<>(fields1);
        fields20.put("merchant_name", "Coffee\u0000Shop");
        list.add(createEncoderErrorVector("encoder_error_control_character", "CHALLENGE_DECISION", fields20, "merchant_name contains control character"));

        // 21. encoder_error_amount_zero
        Map<String, Object> fields21 = new LinkedHashMap<>(fields1);
        fields21.put("amount_minor", 0L);
        list.add(createEncoderErrorVector("encoder_error_amount_zero", "CHALLENGE_DECISION", fields21, "amount must be positive"));

        // 22. encoder_error_lowercase_currency
        Map<String, Object> fields22 = new LinkedHashMap<>(fields1);
        fields22.put("currency", "inr");
        list.add(createEncoderErrorVector("encoder_error_lowercase_currency", "CHALLENGE_DECISION", fields22, "currency must be upper-case 3-letter ISO 4217"));

        // 23. encoder_error_nonce_wrong_length
        Map<String, Object> fields23 = new LinkedHashMap<>(fields1);
        fields23.put("nonce", HEX.formatHex(Arrays.copyOf(nonce32, 31)));
        list.add(createEncoderErrorVector("encoder_error_nonce_wrong_length", "CHALLENGE_DECISION", fields23, "nonce must be exactly 32 bytes"));

        return list;
    }

    private static Map<String, Object> createVector(
            String name,
            String type,
            Map<String, Object> fields,
            byte[] payload,
            KeyBundle key,
            byte[] signature,
            boolean mustVerify
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("fields", fields);
        map.put("payload_hex", HEX.formatHex(payload));
        map.put("payload_sha256_hex", HEX.formatHex(sha256(payload)));
        map.put("public_key_spki_b64url", key.spkiB64Url);
        map.put("signature_der_b64url", B64URL.encodeToString(signature));
        map.put("must_verify", mustVerify);
        return map;
    }

    private static Map<String, Object> createEncoderErrorVector(
            String name,
            String type,
            Map<String, Object> fields,
            String reason
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("type", type);
        map.put("fields", fields);
        map.put("expect_encoder_error", true);
        map.put("error_reason", reason);
        return map;
    }

    static byte[] signDeterministic(ECPrivateKeyParameters privKey, byte[] payload) {
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, privKey);
        byte[] hash = sha256(payload);
        BigInteger[] rs = signer.generateSignature(hash);
        BigInteger r = rs[0];
        BigInteger s = rs[1];

        // Low-s normalization
        BigInteger n = NISTNamedCurves.getByName("P-256").getN();
        BigInteger halfN = n.shiftRight(1);
        if (s.compareTo(halfN) > 0) {
            s = n.subtract(s);
        }

        ASN1EncodableVector vec = new ASN1EncodableVector();
        vec.add(new ASN1Integer(r));
        vec.add(new ASN1Integer(s));
        try {
            return new DERSequence(vec).getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new RuntimeException("Failed to DER encode signature", e);
        }
    }

    static byte[] derToRawRs(byte[] derSignature) {
        try {
            org.bouncycastle.asn1.ASN1Sequence seq = (org.bouncycastle.asn1.ASN1Sequence)
                    org.bouncycastle.asn1.ASN1Primitive.fromByteArray(derSignature);
            BigInteger r = ((ASN1Integer) seq.getObjectAt(0)).getValue();
            BigInteger s = ((ASN1Integer) seq.getObjectAt(1)).getValue();

            byte[] raw = new byte[64];
            copyBigIntegerToFixed32(r, raw, 0);
            copyBigIntegerToFixed32(s, raw, 32);
            return raw;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert DER to raw r||s", e);
        }
    }

    private static void copyBigIntegerToFixed32(BigInteger bi, byte[] target, int offset) {
        byte[] bytes = bi.toByteArray();
        int srcOffset = 0;
        int len = bytes.length;
        if (len > 32) {
            srcOffset = len - 32;
            len = 32;
        }
        int destOffset = offset + (32 - len);
        System.arraycopy(bytes, srcOffset, target, destOffset, len);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
