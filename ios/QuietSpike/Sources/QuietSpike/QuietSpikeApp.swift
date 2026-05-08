// ios/QuietSpike/Sources/QuietSpike/QuietSpikeApp.swift
//
// SwiftUI app entry. Single Scene, single root View — CaptureView.
// No navigation, no settings, no auth UI. The spike is the field.
//
// App-scope wiring mirrors android/.../CaptureActivity.onCreate:
//   sync = SyncStack.create()
//   sync.signInIfNeeded()                          // no-op pre-Precondition B
//   setContent { CaptureScreen(sync = sync) }      // pass down explicitly

import SwiftUI
import os.log

@main
struct QuietSpikeApp: App {
    /// App-scope sync stack. Constructed once at launch; passed into
    /// the view tree so CaptureViewModel doesn't have to know how to
    /// build a store. Optional because `SyncStack.create()` throws if
    /// the local DB can't be opened — in that case CaptureView surfaces
    /// the error to LatencyLog as `commit_error` rather than silently
    /// lying with an empty-field receipt.
    private let sync: SyncStack?

    init() {
        do {
            self.sync = try SyncStack.create()
        } catch {
            self.sync = nil
            Logger(subsystem: "app.quiet.spike", category: "app")
                .error("SyncStack.create failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    var body: some Scene {
        WindowGroup {
            CaptureView(sync: sync)
                .preferredColorScheme(.light)
                .background(QuietTokens.bgCanvas.ignoresSafeArea())
                .onAppear {
                    // No-op until Precondition B lands; matches
                    // CaptureActivity.onCreate's sync.signInIfNeeded().
                    sync?.signInIfNeeded()
                }
        }
    }
}
