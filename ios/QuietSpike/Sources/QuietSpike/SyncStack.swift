// ios/QuietSpike/Sources/QuietSpike/SyncStack.swift
//
// Local-side GRDB + (eventually) Supabase auth + PowerSync sync.
// iOS mirror of android/.../SyncStack.kt; the two files are kept
// structurally identical so a future change to the deferred-but-ready
// pattern lands on both platforms with a copy-paste-level diff.
//
// **Status (commit after 766a50c):** PowerSync wiring is deliberately
// *deferred* until Precondition B (a real Supabase + PowerSync sandbox)
// exists. Unlike Android, the SDK is not even pulled in via SPM yet —
// the PowerSync Swift SDK is iOS BETA and the constructor surface is
// shifting in lockstep with the Kotlin BETA29 surface noted in
// SyncStack.kt. Adding the SPM package without a live instance to
// validate against would risk a red CI on a public repo with no way to
// reproduce the failure on a Windows host. When Precondition B lands:
//
//   1. Add the PowerSync Swift SDK package + the Supabase Swift SDK
//      package to ios/QuietSpike/project.yml under `packages:`.
//   2. Add `PowerSync` and `Supabase` to the QuietSpike target's
//      `dependencies:`.
//   3. Replace this file's `signInIfNeeded()` and `connect()` no-ops
//      with the Supabase Auth + PowerSyncDatabase calls per the SDK's
//      Getting Started guide.
//   4. Add the iOS-side BuildConfig equivalent (Bundle/Info.plist values
//      surfaced from .env via xcodegen INFOPLIST_KEY_* settings) so
//      SUPABASE_URL / POWERSYNC_URL / QUIET_TEST_USER_* are readable.
//   5. Keep `SyncSchema.swift` aligned with `SyncSchema.kt` — Postgres is
//      the source of truth for both.
//
// Scenarios A and B (the deliverable for session 2) measured t_keystroke
// and t_local only — both come from the SwiftUI path + local SQLite, no
// network. Scenarios C/D, t_e2e, and t_converge all need PowerSync; that
// wiring lands when the sandbox is live.

import Foundation
import os.log

/// App-scope holder for the local store and (eventually) the PowerSync
/// + Supabase clients. Construct once at app entry, share down the view
/// tree. Mirrors the role of `SyncStack` in `CaptureActivity.kt`.
final class SyncStack: @unchecked Sendable {
    static let log = Logger(subsystem: "app.quiet.spike", category: "sync")

    /// Local SQLite-via-GRDB. Append-only by mandate (ADR-002); the
    /// PowerSync SDK, when added, will replicate this table's writes
    /// through the upload pipeline rather than replace the store.
    let captureStore: CaptureStore

    private init(captureStore: CaptureStore) {
        self.captureStore = captureStore
    }

    /// Construct the stack. Throws if the local store can't be opened —
    /// we surface that to the caller because the empty-field receipt
    /// would be a lie if writes were silently failing.
    static func create() throws -> SyncStack {
        let store = try CaptureStore()
        return SyncStack(captureStore: store)
    }

    /// No-op until Precondition B lands. When it does, this method
    /// signs in with QUIET_TEST_USER_EMAIL / QUIET_TEST_USER_PASSWORD
    /// via supabase-swift's Auth client and hands the resulting JWT to
    /// the PowerSync `SupabaseConnector`, which PowerSync trusts.
    func signInIfNeeded() {
        // Intentional no-op. See file header for what lands here.
    }

    /// No-op until Precondition B lands. When it does, this method
    /// constructs the `PowerSyncDatabase` against `SyncSchema.schema`
    /// and starts the upload/download streams. Until then the local
    /// GRDB store is the only persistence layer; that's enough for
    /// scenarios A and B.
    func connect() {
        // Intentional no-op. See file header for what lands here.
    }
}
