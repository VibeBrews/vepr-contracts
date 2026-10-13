package io.vepr.contracts.signing;

public enum PayloadType {
    CHALLENGE_DECISION((byte) 0x01),
    PRE_APPROVAL((byte) 0x02),
    DEVICE_AUTH((byte) 0x03),
    ENROLLMENT_BINDING((byte) 0x04);

    private final byte wire;

    PayloadType(byte wire) {
        this.wire = wire;
    }

    public byte wire() {
        return wire;
    }

    public static PayloadType fromWire(byte wire) {
        for (PayloadType type : values()) {
            if (type.wire == wire) {
                return type;
            }
        }
        throw new IllegalArgumentException(String.format("Unknown payload type byte: 0x%02x", wire));
    }
}
