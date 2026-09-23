import Foundation

/// Validates and normalizes a domain string before it's trusted anywhere near
/// enforcement. The daemon writes these lines into /etc/hosts as root, so
/// this is a trust boundary: reject anything that isn't a bare hostname — no
/// scheme, path, whitespace, or characters that could inject extra lines.
public enum DomainValidation {
    private static let allowedCharacters = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")

    /// Returns a normalized lowercase hostname, or nil if the input isn't a
    /// plausible bare domain.
    public static func normalize(_ raw: String) -> String? {
        var domain = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        for prefix in ["https://", "http://"] {
            if domain.hasPrefix(prefix) {
                domain.removeFirst(prefix.count)
            }
        }
        if domain.hasPrefix("www.") {
            domain.removeFirst("www.".count)
        }

        // Strip anything after the host itself: path, port, query, fragment.
        if let cut = domain.firstIndex(where: { "/:?#".contains($0) }) {
            domain = String(domain[domain.startIndex..<cut])
        }

        guard !domain.isEmpty,
              domain.contains("."),
              !domain.hasPrefix("."), !domain.hasSuffix("."),
              !domain.hasPrefix("-"), !domain.hasSuffix("-"),
              domain.unicodeScalars.allSatisfy({ allowedCharacters.contains($0) })
        else {
            return nil
        }

        return domain
    }
}
