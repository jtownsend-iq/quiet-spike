// ios/QuietSpike/Sources/QuietSpike/Ulid.swift
//
// Minimal ULID generator: 48-bit timestamp + 80-bit random, Crockford
// base32. Matches the Android Ulid.kt encoding so server rows stay
// lex-sortable and compare cleanly across platforms.

import Foundation

enum Ulid {
    private static let alphabet: [Character] = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    static func generate(timestampMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)) -> String {
        var rand = [UInt8](repeating: 0, count: 10)
        let result = SecRandomCopyBytes(kSecRandomDefault, rand.count, &rand)
        precondition(result == errSecSuccess, "SecRandomCopyBytes failed")

        var out = [Character](repeating: "0", count: 26)
        var ts = timestampMs
        for i in stride(from: 9, through: 0, by: -1) {
            out[i] = alphabet[Int(ts & 0x1F)]
            ts >>= 5
        }
        // 80 bits across positions 10..25 — split into two UInt64 chunks
        // of 40 bits each (8 base32 chars per chunk).
        var hi: UInt64 = (UInt64(rand[0]) << 32) | (UInt64(rand[1]) << 24) |
                         (UInt64(rand[2]) << 16) | (UInt64(rand[3]) <<  8) | UInt64(rand[4])
        var lo: UInt64 = (UInt64(rand[5]) << 32) | (UInt64(rand[6]) << 24) |
                         (UInt64(rand[7]) << 16) | (UInt64(rand[8]) <<  8) | UInt64(rand[9])
        for i in stride(from: 25, through: 18, by: -1) {
            out[i] = alphabet[Int(lo & 0x1F)]
            lo >>= 5
        }
        for i in stride(from: 17, through: 10, by: -1) {
            out[i] = alphabet[Int(hi & 0x1F)]
            hi >>= 5
        }
        return String(out)
    }
}
