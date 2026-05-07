// ios/QuietSpike/Sources/QuietSpike/QuietTokens.swift
//
// Locked design tokens. Verbatim from CLAUDE.md and the Android target's
// QuietTheme.kt. Colours, type scale, spacing — non-negotiable.

import SwiftUI

enum QuietTokens {
    // Canvas / surfaces
    static let bgCanvas      = Color(hex: 0xFAFAF7)
    static let bgSurface     = Color(hex: 0xFFFFFF)
    static let bgInset       = Color(hex: 0xF2F1EC)

    // Text
    static let textPrimary   = Color(hex: 0x1A1A1A)
    static let textSecondary = Color(hex: 0x4A4A4A)
    static let textTertiary  = Color(hex: 0x7A7A7A)

    // Single accent — focus underline only, never anywhere else.
    static let accentInk     = Color(hex: 0x1F3A5F)

    // The only border weight in the system.
    static let hairline      = Color(hex: 0xE5E3DD)

    // Type scale: 32 / 20 / 16 / 13 / 11; 2 weights only (regular + semibold).
    static let body          = Font.system(size: 16, weight: .regular,  design: .default)
    static let bodyStrong    = Font.system(size: 16, weight: .semibold, design: .default)
    static let meta          = Font.system(size: 11, weight: .regular,  design: .default)

    // Body line height target is 26 pt; SwiftUI sets line height via
    // .lineSpacing (extra above default leading). System default body
    // leading at 16 pt is ~22 pt, so 4 pt extra ≈ 26.
    static let bodyLineSpacing: CGFloat = 4
}

private extension Color {
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >>  8) & 0xFF) / 255.0
        let b = Double( hex        & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: 1.0)
    }
}
