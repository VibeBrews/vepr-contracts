import Foundation

public enum SigningError: Error, Equatable, CustomStringConvertible, Sendable {
    case invalidArgument(String)

    public var description: String {
        switch self {
        case .invalidArgument(let message):
            return message
        }
    }

    public var localizedDescription: String {
        description
    }
}
