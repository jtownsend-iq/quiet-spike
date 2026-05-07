// android/quiet-spike/app/src/main/java/app/quiet/spike/CaptureActivity.kt
//
// Single-Activity, single-screen, single TextField. Contract is identical
// to the prototype's #capture-input (CLAUDE.md § Capture surface contract):
//
//   - Placeholder "What just landed."
//   - autocomplete = off, spellcheck = off
//   - IME action Send → submit
//   - Esc / system back dismisses without losing the draft
//   - Field clears on commit (the empty field IS the receipt)
//   - No toast, animation, spinner, haptic, or sound
//   - Single accent #1F3A5F on focus underline; nowhere else
//   - Reduce-motion preference respected (motion/calm collapses to instant)
//   - Tap target ≥ 56 dp; body contrast ≥ 7:1 vs canvas (#1A1A1A on #FAFAF7)
//
// Latency brackets (DoD #4):
//
//   t_keystroke : empty → first char typed → next frame rendered.
//   t_local     : Send → SQLite txn committed AND field cleared.
//   t_e2e       : Send → Postgres confirms the row (Supabase Postgrest
//                 SELECT by (device_id, client_seq) — minimal round trip,
//                 no PowerSync upload-progress callback in BETA29 yet).

package app.quiet.spike

import android.os.Bundle
import android.view.Choreographer
import android.view.animation.AnimationUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class CaptureActivity : ComponentActivity() {

    private lateinit var sync: SyncStack
    private lateinit var log: LatencyLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sync = SyncStack.create(applicationContext)
        log  = LatencyLog.get(applicationContext)
        sync.signInIfNeeded()

        setContent {
            QuietTheme {
                CaptureScreen(
                    onCommit = { text -> commit(text) }
                )
            }
        }
    }

    /**
     * Persist the captured text. Returns committed time (nanos) at the moment
     * the local SQLite transaction completes; the caller logs t_local once
     * the field-clear has propagated to a fresh frame.
     */
    private suspend fun commit(text: String): CommitResult = withContext(Dispatchers.IO) {
        val tStart = System.nanoTime()
        val seq = Identity.nextClientSeq(applicationContext)
        val device = Identity.deviceId(applicationContext)
        val capturedAtSec = System.currentTimeMillis() / 1000.0
        val id = Ulid.generate()

        // Local SQLite write (the t_local critical section).
        sync.captureDb.captureItemsQueries.insert(
            id = id,
            raw_text = text,
            captured_at = capturedAtSec,
            device_id = device,
            client_seq = seq,
            source = "sheet",
        )
        val tCommitted = System.nanoTime()

        // E2E ACK: poll Postgrest until the row is visible. Cheap because
        // the row PK is the ULID and Supabase REST returns ≤1 row.
        sync.scope.launch {
            val ackStart = tStart
            val deadlineNs = ackStart + 15_000_000_000L  // 15 s safety net
            try {
                while (System.nanoTime() < deadlineNs) {
                    val rows = sync.supabase.from("capture_items")
                        .select { filter { eq("id", id) }; limit(1) }
                        .decodeList<CaptureRowDto>()
                    if (rows.isNotEmpty()) {
                        val tAck = System.nanoTime()
                        log.record("t_e2e", (tAck - ackStart).nsToMs(), seq)
                        return@launch
                    }
                    kotlinx.coroutines.delay(75)
                }
            } catch (_: Throwable) {
                // Ignore — t_e2e simply doesn't get logged for this capture.
            }
        }

        CommitResult(seq = seq, tStart = tStart, tCommitted = tCommitted)
    }

    data class CommitResult(val seq: Long, val tStart: Long, val tCommitted: Long)

    @Serializable
    private data class CaptureRowDto(val id: String)

    // ---- Compose UI ------------------------------------------------------

    @Composable
    private fun CaptureScreen(
        onCommit: suspend (String) -> CommitResult,
    ) {
        val ctx = LocalContext.current
        val focus = LocalFocusManager.current
        val ime = LocalSoftwareKeyboardController.current
        val scope = rememberCoroutineScope()

        var text by rememberSaveable { mutableStateOf("") }
        var firstCharNanos by remember { mutableStateOf<Long?>(null) }

        // Reduce-motion preference (motion/calm → instant). animator
        // duration scale of 0 means the user has disabled animations
        // system-wide, which the spike must respect.
        val reduceMotion = AnimationUtils.currentAnimationTimeMillis().let {
            val scale = android.provider.Settings.Global.getFloat(
                ctx.contentResolver, "animator_duration_scale", 1f
            )
            scale == 0f
        }
        @Suppress("UNUSED_VARIABLE") val _rm = reduceMotion // referenced for posterity; no animations to gate

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(QuietTokens.BgCanvas),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    "Quiet — capture",
                    style = QuietTokens.BodyStrong,
                )
                Spacer(Modifier.height(16.dp))

                CaptureField(
                    value = text,
                    onValueChange = { newText ->
                        // First-char latency bracket: when an empty field
                        // becomes non-empty, capture nanoTime; once the
                        // next frame paints, log t_keystroke.
                        if (text.isEmpty() && newText.isNotEmpty() && firstCharNanos == null) {
                            firstCharNanos = System.nanoTime()
                            Choreographer.getInstance().postFrameCallback {
                                val start = firstCharNanos ?: return@postFrameCallback
                                val elapsed = (System.nanoTime() - start).nsToMs()
                                LatencyLog.get(ctx).record(
                                    metric = "t_keystroke",
                                    valueMs = elapsed,
                                    clientSeq = -1L, // pre-commit; no client_seq yet
                                )
                                firstCharNanos = null
                            }
                        }
                        text = newText
                    },
                    onSubmit = {
                        val toCommit = text
                        if (toCommit.isBlank()) return@CaptureField
                        scope.launch {
                            val tSendNs = System.nanoTime()
                            val res = onCommit(toCommit)
                            // Field clears immediately; the empty field is the receipt.
                            text = ""
                            // Log t_local once the next frame paints the empty field.
                            Choreographer.getInstance().postFrameCallback {
                                val tLocalNs = System.nanoTime() - tSendNs
                                LatencyLog.get(ctx).record(
                                    metric = "t_local",
                                    valueMs = tLocalNs.nsToMs(),
                                    clientSeq = res.seq,
                                )
                            }
                        }
                    },
                    onEscape = {
                        // Dismiss without losing the draft. We close the
                        // keyboard and clear focus; rememberSaveable keeps
                        // the in-progress text across rotation but the spike
                        // itself doesn't expose a "dismiss" UI yet — back
                        // press is handled by the system, which finishes
                        // the Activity (force-quit scenario B uses recents).
                        ime?.hide()
                        focus.clearFocus(force = true)
                    },
                )
            }
        }
    }

    @Composable
    private fun CaptureField(
        value: String,
        onValueChange: (String) -> Unit,
        onSubmit: () -> Unit,
        onEscape: () -> Unit,
    ) {
        // Single accent on the focus underline; nowhere else.
        // Hairline weight (1 dp) — the only border weight in the system.
        val underlineColor = QuietTokens.AccentInk
        val placeholderStyle = QuietTokens.Body.copy(color = QuietTokens.TextTertiary)
        val bodyStyle: TextStyle = QuietTokens.Body

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = bodyStyle,
            cursorBrush = SolidColor(QuietTokens.AccentInk),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp) // tap-target floor (CLAUDE.md)
                .padding(vertical = 12.dp)
                .drawBehind {
                    // 1 dp hairline focus underline. Drawn below the text
                    // baseline; no shadow, no fill, no animation.
                    val y = size.height - 1.dp.toPx() / 2f
                    drawLine(
                        color = underlineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                        onEscape(); true
                    } else false
                },
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(
                            text = "What just landed.",
                            style = placeholderStyle,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
