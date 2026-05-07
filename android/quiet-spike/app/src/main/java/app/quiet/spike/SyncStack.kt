// android/quiet-spike/app/src/main/java/app/quiet/spike/SyncStack.kt
//
// PowerSync + Supabase plumbing for the spike.
//
// Auth: hardcoded test user (CLAUDE.md: "spike uses a hardcoded test user").
// Auth UI is its own spike per SPIKE-01 § "What the spike deliberately does
// not prove". We sign in with email + password from BuildConfig; PowerSync
// trusts the resulting Supabase JWT via the connector.
//
// Locally, every capture is committed to SQLite by SQLDelight inside a single
// transaction. PowerSync's upload queue ships the row to Postgres
// asynchronously and de-dupes retries on (device_id, client_seq) per ADR-002.

package app.quiet.spike

import android.content.Context
import android.util.Log
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.quiet.spike.db.CaptureDatabase
// PowerSync + Supabase imports are wildcarded by the SDK; concrete classes
// resolve at compile time. The names below match the public APIs as of
// PowerSync 1.0.0-BETA29 and supabase-kt 3.x.
// (See https://docs.powersync.com/client-sdk-references/kotlin-multiplatform
// and https://github.com/supabase-community/supabase-kt for the canonical
// usage.)
import com.powersync.PowerSyncDatabase
import com.powersync.connector.supabase.SupabaseConnector
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncStack private constructor(
    private val ctx: Context,
    val supabase: SupabaseClient,
    val captureDb: CaptureDatabase,
    @Suppress("unused") val powerSync: PowerSyncDatabase,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One-shot sign-in with the hardcoded spike user. */
    fun signInIfNeeded() {
        val email = BuildConfig.QUIET_TEST_USER_EMAIL
        val password = BuildConfig.QUIET_TEST_USER_PASSWORD
        if (email.isBlank() || password.isBlank()) {
            Log.w(TAG, "QUIET_TEST_USER_* not set — sign-in skipped. " +
                "Captures still land in local SQLite; PowerSync upload will fail.")
            return
        }
        scope.launch {
            try {
                val auth = supabase.pluginManager.getPlugin(Auth)
                if (auth.currentSessionOrNull() == null) {
                    auth.signInWith(Email) {
                        this.email = email
                        this.password = password
                    }
                    Log.i(TAG, "signed in as $email")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sign-in failed: ${t.message}")
            }
        }
    }

    companion object {
        const val TAG = "QuietSync"

        fun create(ctx: Context): SyncStack {
            // Supabase client — auth + postgrest only.
            val supabase = createSupabaseClient(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
            ) {
                install(Auth)
                install(Postgrest)
            }

            // Local SQLite via SQLDelight. Schema is generated from
            // schema/capture.sql (see app/build.gradle.kts copyCaptureSchema).
            val driver = AndroidSqliteDriver(
                schema = CaptureDatabase.Schema,
                context = ctx,
                name = "capture.db",
            )
            val captureDb = CaptureDatabase(driver)

            // PowerSync replicates Postgres rows into a separate SQLite store
            // and watches local writes for upload. The Supabase connector
            // reads JWTs from the auth plugin we configured above.
            val connector = SupabaseConnector(
                supabaseClient = supabase,
                powerSyncEndpoint = BuildConfig.POWERSYNC_URL,
            )
            val powerSync = PowerSyncDatabase.Builder(ctx, "powersync.db")
                .schema(SyncSchema.SCHEMA)
                .build()
            powerSync.connect(connector)

            return SyncStack(ctx, supabase, captureDb, powerSync)
        }
    }
}
