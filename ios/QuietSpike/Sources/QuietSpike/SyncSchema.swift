// ios/QuietSpike/Sources/QuietSpike/SyncSchema.swift
//
// PowerSync schema — the *replicated* view of capture_items mirrored
// into the device's local SQLite. iOS mirror of android/.../SyncSchema.kt;
// both must stay structurally identical to schema/capture.sql, which is
// the source of truth. supabase/migrations/0001_capture_items.sql is the
// Postgres mirror; supabase/powersync/sync_rules.yaml references the
// same table name.
//
// **Status (commit after 766a50c):** Like SyncStack.swift, this file
// does not yet `import PowerSync` because the iOS BETA SDK is not yet
// in the build (see SyncStack.swift header for the full deferral
// rationale). The columns below are kept as a Swift-native value type
// that mirrors the shape of `com.powersync.db.schema.{Schema,Table,Column}`,
// so the eventual diff to swap in the real PowerSync types is mechanical.
//
// Keep aligned: if a column is added to schema/capture.sql, add it here
// AND in SyncSchema.kt AND in supabase/migrations/0001_capture_items.sql.

import Foundation

enum SyncColumnKind {
    case text
    case real
    case integer
}

struct SyncColumn {
    let name: String
    let kind: SyncColumnKind
}

struct SyncTable {
    let name: String
    let columns: [SyncColumn]
}

struct SyncSchema {
    let tables: [SyncTable]

    /// The shared schema definition. Consumed by PowerSync (once the
    /// SDK is added) to build the local replicated view; until then it
    /// exists for documentation parity with SyncSchema.kt.
    static let schema = SyncSchema(tables: [
        SyncTable(
            name: "capture_items",
            columns: [
                SyncColumn(name: "raw_text",    kind: .text),
                SyncColumn(name: "captured_at", kind: .real),
                SyncColumn(name: "device_id",   kind: .text),
                SyncColumn(name: "client_seq",  kind: .integer),
                SyncColumn(name: "source",      kind: .text),
                // user_id exists in Postgres for RLS but PowerSync hides
                // it from the client view by default; we don't replicate
                // it because the sync rule already filters per-user.
            ]
        )
    ])
}
