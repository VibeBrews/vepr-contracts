package io.vepr.contracts.signing;

public enum Decision {
    APPROVE((byte) 0x01),
    DENY((byte) 0x02);

    private final byte wire;

    Decision(byte wire) {
        this.wire = wire;
    }

    public byte wire() {
        return wire;
    }

    public static Decision fromWire(byte wire) {
        for (Decision d : values()) {
            if (d.wire == wire) {
                return d;
            }
        }
        throw new IllegalArgumentException(String.format("Unknown decision byte: 0x%02x", wire));
    }
}
