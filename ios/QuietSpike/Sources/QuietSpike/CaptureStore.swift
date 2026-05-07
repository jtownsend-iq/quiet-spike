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

    /// Apply schema/capture.sql verbatim. Idempotent — every CREATE
    /// statement uses IF NOT EXISTS so repeated launches are no-ops.
    private func bootstrapSchema() throws {
        guard let url = Bundle.main.url(forResource: "capture", withExtension: "sql") else {
            throw NSError(domain: "QuietSpike", code: 1,
                          userInfo: [NSLocalizedDescriptionKey:
                            "capture.sql missing from bundle — check project.yml resources"])
        }
        let sql = try String(contentsOf: url, encoding: .utf8)
        try dbQueue.write { db in
            try db.execute(sql: sql)
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
