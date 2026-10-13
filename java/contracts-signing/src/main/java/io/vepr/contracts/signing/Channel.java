package io.vepr.contracts.signing;

public enum Channel {
    CARD_PRESENT("card_present"),
    QR("qr");

    private final String wire;

    Channel(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Channel fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("Channel wire representation cannot be null");
        }
        for (Channel c : values()) {
            if (c.wire.equals(wire)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown channel wire value: " + wire);
    }
}
