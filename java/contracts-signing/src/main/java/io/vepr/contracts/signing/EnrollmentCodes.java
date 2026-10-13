package io.vepr.contracts.signing;

import java.util.Locale;

public final class EnrollmentCodes {

    private EnrollmentCodes() {
    }

    /**
     * Normalizes an enrollment code per SPEC §4.4:
     * upper-case, hyphens/spaces removed, O→0, I→1, L→1.
     *
     * @param raw the raw enrollment code string
     * @return the normalized enrollment code
     * @throws IllegalArgumentException if the code is null or normalizes to an empty string
     */
    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Enrollment code cannot be null");
        }
        String normalized = raw.toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace(" ", "")
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1');
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Normalized enrollment code cannot be empty");
        }
        return normalized;
    }
}
