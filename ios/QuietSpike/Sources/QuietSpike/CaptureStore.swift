// ios/QuietSpike/Sources/QuietSpike/CaptureStore.swift
//
// GRDB-backed local store for the spike. The schema-of-record is
// quiet/schema/capture.sql, bundled into the .ipa via project.yml
// resources. We execute it once at first launch (CREATE IF NOT EXISTS,
// so re-running is a no-op).
//
// Append-only by mandate (ADR-002). Insert is the only mutation the
// spike needs; no UPDATE / DELETE.

import Foundation
import GRDB

final class CaptureStore: @unchecked Sendable {
    private let dbQueue: DatabaseQueue

    init() throws {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory,
                                           in: .userDomainMask).first!
        try FileManager.default.createDirectory(at: dir,
                                                withIntermediateDirectories: true)
        let dbURL = dir.appendingPathComponent("capture.db")
        var config = Configuration()
        // Default journal mode is the production hot path — do NOT switch
        // to WAL or sync=OFF here. The spike measures stock semantics so
        // the t_local budget reflects what users will actually feel.
        config.label = "capture.db"
        self.dbQueue = try DatabaseQueue(path: dbURL.path, configuration: config)
        try bootstrapSchema()
    }

    /// Apply the capture schema. Inlined verbatim from schema/capture.sql
    /// rather than bundled — xcodegen's path resolution for files outside
    /// the project tree is fiddly and that's what tripped up the first
    /// device run (run 25469909765's .ipa shipped without capture.sql in
    /// the bundle, so CaptureStore.init threw and the field never cleared).
    /// If schema/capture.sql changes, mirror the change here. The Android
    /// target still consumes schema/capture.sql via SQLDelight; the
    /// :app:verifySchemaInSync gradle task is the canonical drift guard.
    private static let schemaSQL = """
        CREATE TABLE IF NOT EXISTS capture_items (
            id           TEXT    PRIMARY KEY,
            raw_text     TEXT    NOT NULL,
            captured_at  REAL    NOT NULL,
            device_id    TEXT    NOT NULL,
            client_seq   INTEGER NOT NULL,
            source       TEXT    NOT NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS ux_capture_items_device_seq
            ON capture_items (device_id, client_seq);
        CREATE INDEX IF NOT EXISTS ix_capture_items_captured_at
            ON capture_items (captured_at);
        """

    private func bootstrapSchema() throws {
        try dbQueue.write { db in
            try db.execute(sql: Self.schemaSQL)
        }
    }

    /// Single-row INSERT. Returns *after* the GRDB write closure — and
    /// since GRDB wraps each .write{} in a transaction with
    /// PRAGMA synchronous=FULL by default, the row is fsync'd before
    /// this function returns. That's the t_local critical section.
    func insert(id: String,
                rawText: String,
                capturedAt: Double,
                deviceId: String,
                clientSeq: Int64,
                source: String) throws {
        try dbQueue.write { db in
            try db.execute(
                sql: """
                    INSERT INTO capture_items
                        (id, raw_text, captured_at, device_id, client_seq, source)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                arguments: [id, rawText, capturedAt, deviceId, clientSeq, source]
            )
        }
    }
}
