// android/quiet-spike/app/src/main/java/app/quiet/spike/SyncSchema.kt
//
// PowerSync schema — the *replicated* view of capture_items mirrored into
// the device's local SQLite. Mirrors schema/capture.sql; if you add a
// column there, mirror it here. The schema name "capture_items" must
// match the Postgres table referenced by supabase/powersync/sync_rules.yaml.

package app.quiet.spike

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

object SyncSchema {
    val SCHEMA = Schema(
        listOf(
            Table(
                name = "capture_items",
                columns = listOf(
                    Column.text("raw_text"),
                    Column.real("captured_at"),
                    Column.text("device_id"),
                    Column.integer("client_seq"),
                    Column.text("source"),
                    // user_id exists in Postgres for RLS but PowerSync hides
                    // it from the client view by default; we don't replicate
                    // it because the sync rule already filters per-user.
                ),
            ),
        )
    )
}
