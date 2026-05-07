// ios/QuietSpike/Sources/QuietSpike/QuietSpikeApp.swift
//
// SwiftUI app entry. Single Scene, single root View — CaptureView.
// No navigation, no settings, no auth UI. The spike is the field.

import SwiftUI

@main
struct QuietSpikeApp: App {
    var body: some Scene {
        WindowGroup {
            CaptureView()
                .preferredColorScheme(.light)
                .background(QuietTokens.bgCanvas.ignoresSafeArea())
        }
    }
}
