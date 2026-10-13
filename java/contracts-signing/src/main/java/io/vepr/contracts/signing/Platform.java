package io.vepr.contracts.signing;

public enum Platform {
    IOS("ios"),
    VIRTUAL("virtual");

    private final String wire;

    Platform(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Platform fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("Platform wire representation cannot be null");
        }
        for (Platform p : values()) {
            if (p.wire.equals(wire)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown platform wire value: " + wire);
    }
}
