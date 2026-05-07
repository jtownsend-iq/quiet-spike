// android/quiet-spike/app/src/main/java/app/quiet/spike/SyncStack.kt
//
// Local-side SQLite + (eventually) Supabase auth + PowerSync sync.
//
// **Status (commit after fe1c478):** PowerSync wiring is deliberately
// *deferred* until Precondition B (a real Supabase + PowerSync sandbox)
// exists. The SDK artifacts are still pulled in via libs.versions.toml so
// dependency resolution stays validated by CI; we just don't construct
// PowerSyncDatabase yet because the BETA29 constructor surface is shifting
// and the only honest way to pin it is against a live PowerSync instance.
// Scenarios A and B (the deliverable for session 2) measure t_keystroke
// and t_local only — both come from the Compose path + local SQLite, no
// network. t_e2e, t_converge, scenarios C/D all need PowerSync; that
// wiring lands when the sandbox is live.
//
// Auth is similarly deferred: we'd sign in with the hardcoded test user
// from BuildConfig once it's set, but with no Supabase project to talk
// to there's nothing to sign in against.

package app.quiet.spike

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.quiet.spike.db.CaptureDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SyncStack private constructor(
    @Suppress("unused") private val ctx: Context,
    val captureDb: CaptureDatabase,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * No-op until Precondition B lands. When it does, this method will
     * sign in with QUIET_TEST_USER_EMAIL / QUIET_TEST_USER_PASSWORD via
     * supabase-kt's Auth plugin and hand the resulting JWT to the
     * SupabaseConnector, which PowerSync trusts.
     */
    fun signInIfNeeded() = Unit

    companion object {
        const val TAG = "QuietSync"

        fun create(ctx: Context): SyncStack {
            // Local SQLite via SQLDelight. Schema is generated from
            // schema/capture.sql (see app/build.gradle.kts copyCaptureSchema);
            // labeled queries live alongside it in Capture.sq.
            val driver = AndroidSqliteDriver(
                schema = CaptureDatabase.Schema,
                context = ctx,
                name = "capture.db",
            )
            val captureDb = CaptureDatabase(driver)
            return SyncStack(ctx, captureDb)
        }
    }
}
