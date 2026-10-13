package io.vepr.contracts.signing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PayloadEncoder {

    private static final byte[] MAGIC = new byte[]{'V', 'E', 'P', 'R'};
    private static final byte VERSION = 0x01;

    private PayloadEncoder() {
    }

    public static byte[] challengeDecision(
            String tenantId,
            UUID challengeId,
            UUID deviceId,
            byte[] nonce,
            String merchantId,
            String merchantName,
            long amountMinor,
            String currency,
            Channel channel,
            Instant expiresAt,
            Decision decision
    ) {
        validateAmount(amountMinor);
        validateCurrency(currency);
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        Objects.requireNonNull(decision, "decision cannot be null");

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeHeader(out, PayloadType.CHALLENGE_DECISION);
        writeStr(out, tenantId, "tenant_id");
        writeUuid(out, challengeId, "challenge_id");
        writeUuid(out, deviceId, "device_id");
        writeNonce32(out, nonce);
        writeStr(out, merchantId, "merchant_id");
        writeStr(out, merchantName, "merchant_name");
        writeI64(out, amountMinor);
        writeStr(out, currency, "currency");
        writeStr(out, channel.wire(), "channel");
        writeTimeMs(out, expiresAt);
        writeU8(out, decision.wire());
        return out.toByteArray();
    }

    public static byte[] preApproval(
            String tenantId,
            UUID preApprovalId,
            UUID deviceId,
            byte[] nonce,
            long maxAmountMinor,
            String currency,
            Channel channelScope,
            Instant expiresAt
    ) {
        validateAmount(maxAmountMinor);
        validateCurrency(currency);
        Objects.requireNonNull(channelScope, "channelScope cannot be null");
        if (channelScope != Channel.CARD_PRESENT) {
            throw new IllegalArgumentException("channel_scope v1 allows only card_present, got: " + channelScope);
        }
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeHeader(out, PayloadType.PRE_APPROVAL);
        writeStr(out, tenantId, "tenant_id");
        writeUuid(out, preApprovalId, "pre_approval_id");
        writeUuid(out, deviceId, "device_id");
        writeNonce32(out, nonce);
        writeI64(out, maxAmountMinor);
        writeStr(out, currency, "currency");
        writeStr(out, channelScope.wire(), "channel_scope");
        writeTimeMs(out, expiresAt);
        return out.toByteArray();
    }

    public static byte[] deviceAuth(
            String tenantId,
            UUID challengeId,
            UUID deviceId,
            byte[] nonce,
            Instant expiresAt
    ) {
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        writeHeader(out, PayloadType.DEVICE_AUTH);
        writeStr(out, tenantId, "tenant_id");
        writeUuid(out, challengeId, "challenge_id");
        writeUuid(out, deviceId, "device_id");
        writeNonce32(out, nonce);
        writeTimeMs(out, expiresAt);
        return out.toByteArray();
    }

    public static byte[] enrollmentBinding(
            String tenantId,
            String enrollmentCode,
            Platform platform,
            byte[] deviceKeySpki,
            byte[] approvalKeySpki
    ) {
        Objects.requireNonNull(platform, "platform cannot be null");
        String normalizedCode = EnrollmentCodes.normalize(enrollmentCode);

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        writeHeader(out, PayloadType.ENROLLMENT_BINDING);
        writeStr(out, tenantId, "tenant_id");
        writeStr(out, normalizedCode, "enrollment_code");
        writeStr(out, platform.wire(), "platform");
        writeBlob(out, deviceKeySpki, "device_key_spki");
        writeBlob(out, approvalKeySpki, "approval_key_spki");
        return out.toByteArray();
    }

    // --- Encoding primitives ---

    private static void writeHeader(ByteArrayOutputStream out, PayloadType type) {
        out.write(MAGIC, 0, MAGIC.length);
        out.write(VERSION);
        out.write(type.wire());
    }

    static void writeStr(ByteArrayOutputStream out, String s, String fieldName) {
        if (s == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        if (!Normalizer.isNormalized(s, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(fieldName + " is not NFC-normalized: " + s);
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if ((cp >= 0x00 && cp <= 0x1F) || (cp >= 0x7F && cp <= 0x9F)) {
                throw new IllegalArgumentException(String.format(
                        "%s contains control character: U+%04X", fieldName, cp));
            }
            i += Character.charCount(cp);
        }

        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length == 0) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        if (utf8.length > 1024) {
            throw new IllegalArgumentException(String.format(
                    "%s UTF-8 length exceeds 1024 bytes: %d", fieldName, utf8.length));
        }

        out.write((utf8.length >>> 8) & 0xFF);
        out.write(utf8.length & 0xFF);
        out.write(utf8, 0, utf8.length);
    }

    static void writeBlob(ByteArrayOutputStream out, byte[] b, String fieldName) {
        if (b == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (b.length > 1024) {
            throw new IllegalArgumentException(String.format(
                    "%s length exceeds 1024 bytes: %d", fieldName, b.length));
        }
        out.write((b.length >>> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.write(b, 0, b.length);
    }

    static void writeUuid(ByteArrayOutputStream out, UUID uuid, String fieldName) {
        if (uuid == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        byte[] bytes = bb.array();
        out.write(bytes, 0, bytes.length);
    }

    static void writeNonce32(ByteArrayOutputStream out, byte[] nonce) {
        if (nonce == null || nonce.length != 32) {
            throw new IllegalArgumentException(String.format(
                    "Nonce must be exactly 32 bytes, got: %s", nonce == null ? "null" : nonce.length));
        }
        out.write(nonce, 0, nonce.length);
    }

    static void writeI64(ByteArrayOutputStream out, long value) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(value);
        byte[] bytes = bb.array();
        out.write(bytes, 0, bytes.length);
    }

    static void writeTimeMs(ByteArrayOutputStream out, Instant instant) {
        writeI64(out, instant.toEpochMilli());
    }

    static void writeU8(ByteArrayOutputStream out, byte b) {
        out.write(b & 0xFF);
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive (> 0), got: " + amount);
        }
    }

    private static void validateCurrency(String currency) {
        if (currency == null || currency.length() != 3 || !currency.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw new IllegalArgumentException(
                    "Currency must be an upper-case 3-letter ISO 4217 code, got: " + currency);
        }
    }
}
