// android/quiet-spike/app/src/main/java/app/quiet/spike/LatencyLog.kt
//
// SPIKE-01 latency harness (DoD #4).
//
// We emit one JSON line per measurement to
//   getExternalFilesDir(null)/latency.jsonl
// which adb pulls from
//   /sdcard/Android/data/app.quiet.spike/files/latency.jsonl
// after each scenario. The shape matches what spike/analyze.py and
// tests/fixtures/_verify_targets.mjs already consume verbatim:
//
//   {"device_id":"…","scenario":"A","metric":"t_keystroke",
//    "value_ms":123.4,"client_seq":42,"wall_clock":"2026-…Z"}
//
// Brackets are explicit and named. The PowerSync SDK does not get a vote
// on what counts as "perceived latency" — we own the brackets:
//
//   t_keystroke : nanoTime() at first-char-of-empty-field → after the
//                 next frame is reported via Choreographer.postFrameCallback.
//                 Captures the first-keystroke jank, which is what the
//                 user feels.
//   t_local     : nanoTime() at IME action Send → after the SQLDelight
//                 INSERT transaction commits AND the field-clear has
//                 propagated to a fresh frame. fsync semantics on stock
//                 SQLite are FULL by default; this is exactly the
//                 budget t_local guards.
//   t_e2e       : nanoTime() at IME action Send → after the server has
//                 ACKed the row through PowerSync's upload pipeline.
//   t_converge  : (Session 4 — D scenario, two-device. Stub here.)

package app.quiet.spike

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LatencyLog private constructor(private val ctx: Context) {

    private val file = run {
        val dir = ctx.getExternalFilesDir(null)
            ?: ctx.filesDir // fallback if external storage is unavailable
        java.io.File(dir, "latency.jsonl")
    }

    @Volatile private var scenario: String = "A"

    fun setScenario(s: String) { scenario = s }
    fun currentScenario(): String = scenario
    fun logFile(): java.io.File = file

    private val isoUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Append one measurement to latency.jsonl. */
    fun record(metric: String, valueMs: Double, clientSeq: Long) {
        val device = Identity.deviceId(ctx)
        val line = buildString(160) {
            append('{')
            kv("device_id", device); append(',')
            kv("scenario", scenario); append(',')
            kv("metric", metric); append(',')
            append("\"value_ms\":")
            // Avoid locale-aware formatting; analyze.py reads JSON numbers.
            append(String.format(Locale.US, "%.3f", valueMs)); append(',')
            append("\"client_seq\":").append(clientSeq); append(',')
            kv("wall_clock", isoUtc.format(Date()))
            append('}')
            append('\n')
        }
        try {
            // O_APPEND on the underlying fd. Each write is short, so we
            // open/close per record — ensures kill -9 between captures
            // doesn't lose buffered measurements.
            FileOutputStream(file, /* append = */ true).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { w ->
                    w.write(line)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "latency write failed: ${t.message}")
        }
    }

    private fun StringBuilder.kv(k: String, v: String) {
        append('"').append(k).append("\":\"").append(escape(v)).append('"')
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    companion object {
        const val TAG = "QuietLatency"
        @Volatile private var instance: LatencyLog? = null
        fun get(ctx: Context): LatencyLog {
            return instance ?: synchronized(this) {
                instance ?: LatencyLog(ctx.applicationContext).also { instance = it }
            }
        }
    }
}

/** Convert nanos to fractional milliseconds (preserves sub-ms resolution). */
fun Long.nsToMs(): Double = this.toDouble() / 1_000_000.0
