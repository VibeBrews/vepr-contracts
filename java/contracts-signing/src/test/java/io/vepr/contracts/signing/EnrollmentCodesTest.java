package io.vepr.contracts.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnrollmentCodesTest {

    @ParameterizedTest(name = "normalize(\"{0}\") -> \"{1}\"")
    @CsvSource({
            "code-1234-ol, C0DE123401",
            "CODE-1234-OL, C0DE123401",
            "oil, 011",
            "OIL, 011",
            "'  abc - def  ', ABCDEF",
            "'O-I-L-o-i-l', 011011",
            "123456, 123456",
            "'HELLO WORLD', HE110W0R1D"
    })
    void testNormalizationRules(String raw, String expected) {
        assertThat(EnrollmentCodes.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Reject null or blank/empty enrollment codes")
    void testRejections() {
        assertThatThrownBy(() -> EnrollmentCodes.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnrollmentCodes.normalize(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnrollmentCodes.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnrollmentCodes.normalize("---"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnrollmentCodes.normalize(" - - "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
