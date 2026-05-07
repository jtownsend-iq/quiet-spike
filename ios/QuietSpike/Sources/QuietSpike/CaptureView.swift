// ios/QuietSpike/Sources/QuietSpike/CaptureView.swift
//
// Single-screen, single-TextField capture surface. Contract verbatim
// from CLAUDE.md § Capture surface contract:
//
//   - Placeholder "What just landed."
//   - autocorrect off, smart quotes off, capitalization none
//   - Submit label .send → onSubmit
//   - Field clears on commit (the empty field IS the receipt)
//   - No toast, no animation, no spinner, no haptic, no sound
//   - Single accent #1F3A5F on the focus underline; nowhere else
//   - Reduce Motion respected (animations are absent regardless)
//   - Tap target ≥ 56 pt; body 16/26 ≥ 7:1 contrast vs canvas
//
// Latency brackets:
//
//   t_keystroke : empty → first char typed → next CADisplayLink tick.
//                 Captures first-keystroke jank — what the user feels.
//   t_local     : Send → GRDB write closure returns AND field cleared.
//                 GRDB defaults synchronous=FULL inside a transaction,
//                 so this matches the production fsync semantics.
//   t_e2e       : DEFERRED until Precondition B is live.

import SwiftUI
import QuartzCore
import os.log

struct CaptureView: View {
    @State private var text: String = ""
    @State private var firstCharStart: UInt64? = nil
    @FocusState private var focused: Bool

    @StateObject private var model = CaptureViewModel()

    private static let viewLog = Logger(subsystem: "app.quiet.spike", category: "view")

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Quiet — capture")
                .font(QuietTokens.bodyStrong)
                .foregroundStyle(QuietTokens.textPrimary)

            captureField
                .frame(minHeight: 56)
                .focused($focused)

            Spacer()
        }
        .padding(.horizontal, 24)
        .padding(.top, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(QuietTokens.bgCanvas)
        .onAppear { focused = true }
    }

    private var captureField: some View {
        TextField("", text: Binding(
            get: { text },
            set: { newValue in
                // First-char latency bracket: when an empty field gains
                // its first character, mark t0; the next display-link
                // tick logs t_keystroke.
                if text.isEmpty, !newValue.isEmpty, firstCharStart == nil {
                    firstCharStart = mach_absolute_time()
                    nextDisplayLinkTick { now in
                        guard let start = firstCharStart else { return }
                        let elapsedNs = machDeltaNs(start: start, end: now)
                        LatencyLog.shared.record(
                            metric: "t_keystroke",
                            valueMs: elapsedNs.nsToMs(),
                            clientSeq: -1 // pre-commit; no client_seq yet
                        )
                        firstCharStart = nil
                    }
                }
                text = newValue
            }
        ), prompt: Text("What just landed.")
            .foregroundStyle(QuietTokens.textTertiary))
        .font(QuietTokens.body)
        .foregroundStyle(QuietTokens.textPrimary)
        .lineSpacing(QuietTokens.bodyLineSpacing)
        .tint(QuietTokens.accentInk)              // cursor + selection accent
        .submitLabel(.send)
        .autocorrectionDisabled(true)
        .textInputAutocapitalization(.never)
        .keyboardType(.default)
        .onSubmit {
            let toCommit = text
            guard !toCommit.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            let tSendNs = mach_absolute_time()
            do {
                let seq = try model.commit(text: toCommit)
                text = ""
                // t_local: log once the next frame paints the empty field.
                nextDisplayLinkTick { now in
                    let elapsedNs = machDeltaNs(start: tSendNs, end: now)
                    LatencyLog.shared.record(
                        metric: "t_local",
                        valueMs: elapsedNs.nsToMs(),
                        clientSeq: seq
                    )
                }
            } catch {
                Self.viewLog.error("commit failed: \(error.localizedDescription, privacy: .public)")
            }
        }
        .padding(.vertical, 12)
        .overlay(alignment: .bottom) {
            // Single 1 pt hairline focus underline; the only place the
            // accent appears in the UI.
            Rectangle()
                .fill(QuietTokens.accentInk)
                .frame(height: 1)
        }
    }

    // ---- Display-link tick helpers ----------------------------------------

    /// Schedule a one-shot callback fired on the next display refresh.
    /// CADisplayLink is the SwiftUI/UIKit equivalent of Android's
    /// Choreographer.postFrameCallback — it fires once per vsync.
    private func nextDisplayLinkTick(_ body: @escaping (UInt64) -> Void) {
        OneShotDisplayLink.schedule { body(mach_absolute_time()) }
    }
}

// One-shot bridge over CADisplayLink. Owns its own NSObject target so
// the proxy doesn't leak after firing.
final class OneShotDisplayLink: NSObject {
    private var link: CADisplayLink?
    private let body: () -> Void
    private init(_ body: @escaping () -> Void) { self.body = body }

    static func schedule(_ body: @escaping () -> Void) {
        let one = OneShotDisplayLink(body)
        let link = CADisplayLink(target: one, selector: #selector(tick))
        one.link = link
        link.add(to: .main, forMode: .common)
    }

    @objc private func tick() {
        link?.invalidate(); link = nil
        body()
    }
}

// Convert mach absolute time deltas to nanoseconds.
private let machInfo: mach_timebase_info_data_t = {
    var info = mach_timebase_info_data_t()
    mach_timebase_info(&info)
    return info
}()

private func machDeltaNs(start: UInt64, end: UInt64) -> UInt64 {
    let elapsed = end &- start
    return elapsed &* UInt64(machInfo.numer) / UInt64(machInfo.denom)
}

@MainActor
final class CaptureViewModel: ObservableObject {
    private let store: CaptureStore?

    init() {
        do {
            self.store = try CaptureStore()
        } catch {
            self.store = nil
            Logger(subsystem: "app.quiet.spike", category: "viewModel")
                .error("CaptureStore init failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Persist a single capture and return its client_seq.
    /// Throws if the store failed to initialise (in which case nothing
    /// has been captured — the empty-field receipt would be a lie).
    func commit(text: String) throws -> Int64 {
        guard let store = store else {
            throw NSError(domain: "QuietSpike", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "CaptureStore unavailable"])
        }
        let seq = Identity.nextClientSeq()
        let device = Identity.deviceId()
        let id = Ulid.generate()
        let capturedAt = Date().timeIntervalSince1970
        try store.insert(
            id: id,
            rawText: text,
            capturedAt: capturedAt,
            deviceId: device,
            clientSeq: seq,
            source: "sheet"
        )
        return seq
    }
}
