// android/quiet-spike/app/src/main/java/app/quiet/spike/Identity.kt
//
// device_id is a stable per-install UUID stored in EncryptedSharedPreferences.
// We don't reuse Android's SSAID — it's reset on factory wipe but also
// shared across apps signed with the same key, and we want each install
// to be independent in the latency log.
//
// client_seq is a monotonically increasing counter, also persisted, that
// pairs with device_id to form the (device_id, client_seq) idempotency
// key referenced by ADR-002 and proto/capture.proto.

package app.quiet.spike

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object Identity {
    private const val FILE = "quiet_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CLIENT_SEQ = "client_seq"

    @Volatile private var cachedDeviceId: String? = null
    private val seqCache = AtomicLong(-1L)

    fun deviceId(ctx: Context): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            val prefs = prefs(ctx)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            val id = existing ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_DEVICE_ID, it).apply()
            }
            cachedDeviceId = id
            return id
        }
    }

    /** Returns the next client_seq atomically and persists it. */
    fun nextClientSeq(ctx: Context): Long {
        val prefs = prefs(ctx)
        if (seqCache.get() < 0) {
            synchronized(this) {
                if (seqCache.get() < 0) {
                    seqCache.set(prefs.getLong(KEY_CLIENT_SEQ, 0L))
                }
            }
        }
        val next = seqCache.incrementAndGet()
        // Sync write so a kill -9 between commit and write doesn't lose the
        // counter — duplicate (device_id, client_seq) is worse than a gap.
        prefs.edit().putLong(KEY_CLIENT_SEQ, next).commit()
        return next
    }

    private fun prefs(ctx: Context) = run {
        val mk = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx, FILE, mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
