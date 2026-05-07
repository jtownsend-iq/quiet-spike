// ios/QuietSpike/Sources/QuietSpike/LatencyLog.swift
//
// SPIKE-01 latency harness — iOS half. Emits the exact same JSON shape
// as android/quiet-spike/.../LatencyLog.kt so spike/analyze.py and
// tests/fixtures/_verify_targets.mjs read both verbatim:
//
//   {"device_id":"…","scenario":"A","metric":"t_keystroke",
//    "value_ms":123.4,"client_seq":42,"wall_clock":"2026-…Z"}
//
// Each measurement is appended to:
//   <Documents>/latency.jsonl
// AND printed to OSLog with the prefix "QUIET_LATENCY " so
// `idevicesyslog | Select-String "QUIET_LATENCY"` (TOOLCHAIN.md §5)
// catches it in real-time over USB.
//
// Brackets explicit and named:
//   t_keystroke : empty → first char typed → next CADisplayLink tick
//                 (the iOS analogue of Android's Choreographer frame).
//   t_local     : Send → SQLite txn committed AND field cleared.
//   t_e2e       : DEFERRED until Precondition B is live (no PowerSync
//                 wiring this session — same deferral as Android, see
//                 spike/results/SESSION-2-BLOCKERS.md).

import Foundation
import os.log

final class LatencyLog: @unchecked Sendable {
    static let shared = LatencyLog()
    private init() {}

    private let queue = DispatchQueue(label: "app.quiet.spike.LatencyLog", qos: .utility)
    private let logger = Logger(subsystem: "app.quiet.spike", category: "latency")
    private var scenario: String = "A"

    private lazy var fileURL: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return dir.appendingPathComponent("latency.jsonl")
    }()

    func setScenario(_ s: String) { queue.sync { scenario = s } }
    func currentScenario() -> String { queue.sync { scenario } }
    func filePath() -> URL { fileURL }

    func record(metric: String, valueMs: Double, clientSeq: Int64) {
        let device = Identity.deviceId()
        let scen = currentScenario()
        let wall = Self.iso8601.string(from: Date())
        // Locale.invariant — Swift's String(format:) without an explicit
        // locale uses POSIX. Force "en_US_POSIX" so 1.5 isn't formatted
        // as "1,5" on a German phone.
        let valueStr = String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), valueMs)
        let line = #"{"device_id":"\#(escape(device))","scenario":"\#(escape(scen))","metric":"\#(escape(metric))","value_ms":\#(valueStr),"client_seq":\#(clientSeq),"wall_clock":"\#(escape(wall))"}"# + "\n"

        // OSLog (real-time over USB via idevicesyslog).
        logger.info("QUIET_LATENCY \(line, privacy: .public)")

        // File append (durable; pulled by Files.app or Apple Configurator
        // post-run). O_APPEND so concurrent writes are atomic at line size.
        queue.async { [self] in
            do {
                if !FileManager.default.fileExists(atPath: fileURL.path) {
                    FileManager.default.createFile(atPath: fileURL.path, contents: nil)
                }
                let handle = try FileHandle(forWritingTo: fileURL)
                defer { try? handle.close() }
                try handle.seekToEnd()
                if let data = line.data(using: .utf8) {
                    try handle.write(contentsOf: data)
                }
            } catch {
                logger.error("latency write failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    private func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: "\"", with: "\\\"")
         .replacingOccurrences(of: "\n", with: "\\n")
    }

    private static let iso8601: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}

/// Convert nanoseconds (Int64 / UInt64) to fractional milliseconds.
extension UInt64 {
    func nsToMs() -> Double { Double(self) / 1_000_000.0 }
}
extension Int64 {
    func nsToMs() -> Double { Double(self) / 1_000_000.0 }
}
