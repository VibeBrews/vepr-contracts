import Testing
@testable import VeprSigning

@Suite("EnrollmentCodes Tests")
struct EnrollmentCodesTests {

    @Test("Normalization rules", arguments: [
        ("code-1234-ol", "C0DE123401"),
        ("CODE-1234-OL", "C0DE123401"),
        ("oil", "011"),
        ("OIL", "011"),
        ("  abc - def  ", "ABCDEF"),
        ("O-I-L-o-i-l", "011011"),
        ("123456", "123456"),
        ("HELLO WORLD", "HE110W0R1D")
    ])
    func normalizationRules(raw: String, expected: String) throws {
        let normalized = try EnrollmentCodes.normalize(raw)
        #expect(normalized == expected)
    }

    @Test("Rejections for empty or non-normalizable codes", arguments: [
        "",
        "   ",
        "---",
        " - - "
    ])
    func rejections(raw: String) {
        #expect(throws: SigningError.self) {
            try EnrollmentCodes.normalize(raw)
        }
    }
}
