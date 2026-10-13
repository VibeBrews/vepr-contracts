package io.vepr.contracts.signing;

import java.util.Objects;

public final class KeyRules {

    private static final byte[] EXPECTED_MAGIC = new byte[]{'V', 'E', 'P', 'R'};
    private static final byte EXPECTED_VERSION = 0x01;

    private KeyRules() {
    }

    /**
     * Checks whether the given {@link KeyType} is permitted to sign the provided payload
     * according to SPEC §5.
     *
     * Validates the header (magic, version, type) and, for {@code CHALLENGE_DECISION} (type 0x01),
     * inspects the trailing decision byte. Does not parse any other fields.
     *
     * @param payload the raw payload bytes
     * @param keyType the key type (DEVICE or APPROVAL)
     * @return true if allowed, false if not allowed
     * @throws IllegalArgumentException if the header is invalid, version/type is unknown,
     *                                  or the decision byte is invalid
     */
    public static boolean isAllowed(byte[] payload, KeyType keyType) {
        Objects.requireNonNull(payload, "payload cannot be null");
        Objects.requireNonNull(keyType, "keyType cannot be null");

        if (payload.length < 6) {
            throw new IllegalArgumentException(
                    "Payload length (" + payload.length + ") is shorter than required 6-byte header");
        }

        // Header check: magic
        for (int i = 0; i < 4; i++) {
            if (payload[i] != EXPECTED_MAGIC[i]) {
                throw new IllegalArgumentException(String.format(
                        "Invalid magic byte at offset %d: expected 0x%02x, got 0x%02x",
                        i, EXPECTED_MAGIC[i], payload[i]));
            }
        }

        // Header check: version
        byte version = payload[4];
        if (version != EXPECTED_VERSION) {
            throw new IllegalArgumentException(String.format(
                    "Unsupported payload version: 0x%02x (expected 0x%02x)", version, EXPECTED_VERSION));
        }

        // Header check: type
        byte typeByte = payload[5];
        PayloadType type = PayloadType.fromWire(typeByte);

        return switch (type) {
            case CHALLENGE_DECISION -> {
                if (payload.length < 7) {
                    throw new IllegalArgumentException(
                            "CHALLENGE_DECISION payload too short to contain trailing decision byte");
                }
                byte decisionByte = payload[payload.length - 1];
                Decision decision = Decision.fromWire(decisionByte);
                yield switch (decision) {
                    case APPROVE -> keyType == KeyType.APPROVAL;
                    case DENY -> keyType == KeyType.DEVICE;
                };
            }
            case PRE_APPROVAL -> keyType == KeyType.APPROVAL;
            case DEVICE_AUTH -> keyType == KeyType.DEVICE;
            case ENROLLMENT_BINDING -> false; // Hashed, never signed by Vepr keys
        };
    }
}
