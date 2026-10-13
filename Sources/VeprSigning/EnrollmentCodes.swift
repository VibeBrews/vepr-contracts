import Foundation

public enum EnrollmentCodes {
    /// Normalizes an enrollment code per SPEC §4.4:
    /// upper-case, hyphens/spaces removed, O→0, I→1, L→1.
    ///
    /// - Parameter raw: the raw enrollment code string
    /// - Returns: the normalized enrollment code
    /// - Throws: `SigningError.invalidArgument` if the code normalizes to an empty string
    public static func normalize(_ raw: String) throws -> String {
        let upper = raw.uppercased()
        var normalized = ""
        for char in upper {
            switch char {
            case "-", " ":
                continue
            case "O":
                normalized.append("0")
            case "I", "L":
                normalized.append("1")
            default:
                normalized.append(char)
            }
        }
        guard !normalized.isEmpty else {
            throw SigningError.invalidArgument("Normalized enrollment code cannot be empty")
        }
        return normalized
    }
}
