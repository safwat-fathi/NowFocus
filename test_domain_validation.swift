import Foundation

// `swift` ignores top-level code in any file but the first when given multiple
// files, so concatenate before running:
// cat apps/macos/NowFocusCore/DomainValidation.swift test_domain_validation.swift > /tmp/t.swift && swift /tmp/t.swift

func check(_ input: String, _ expected: String?, line: Int = #line) {
    let got = DomainValidation.normalize(input)
    guard got == expected else {
        print("FAIL (line \(line)): normalize(\"\(input)\") = \(String(describing: got)), expected \(String(describing: expected))")
        exit(1)
    }
}

check("youtube.com", "youtube.com")
check("https://www.youtube.com", "youtube.com")
check("youtube.com/watch?v=abc", "youtube.com")
check("HTTP://Reddit.COM/r/all", "reddit.com")
check("  twitter.com  ", "twitter.com")
check("evil.com\n1.2.3.4 bank.com", nil) // newline/space injection must be rejected
check("not a domain", nil)
check("", nil)
check("-bad.com", nil)

print("DomainValidation: all checks passed")
