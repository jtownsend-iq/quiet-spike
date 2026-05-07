// android/quiet-spike/app/src/main/java/app/quiet/spike/Ulid.kt
//
// Minimal ULID generator — 48-bit timestamp + 80-bit random, Crockford
// base32. Lex-sortable, monotonically increases with wall clock, no
// external dependency. capture.sql declares id TEXT PK and the iOS half
// will use the same encoding so server rows compare cleanly.

package app.quiet.spike

import java.security.SecureRandom

object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val rng = SecureRandom()

    fun generate(timestampMs: Long = System.currentTimeMillis()): String {
        require(timestampMs >= 0) { "timestamp must be non-negative" }
        val rand = ByteArray(10).also(rng::nextBytes)
        val out = CharArray(26)
        // 10 chars of timestamp (48 bits)
        var ts = timestampMs
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(ts and 0x1F).toInt()]
            ts = ts ushr 5
        }
        // 16 chars of randomness (80 bits)
        // Treat the 10 random bytes as a big-endian 80-bit integer.
        var hi = ((rand[0].toLong() and 0xFF) shl 32) or
                 ((rand[1].toLong() and 0xFF) shl 24) or
                 ((rand[2].toLong() and 0xFF) shl 16) or
                 ((rand[3].toLong() and 0xFF) shl 8) or
                  (rand[4].toLong() and 0xFF)
        var lo = ((rand[5].toLong() and 0xFF) shl 32) or
                 ((rand[6].toLong() and 0xFF) shl 24) or
                 ((rand[7].toLong() and 0xFF) shl 16) or
                 ((rand[8].toLong() and 0xFF) shl 8) or
                  (rand[9].toLong() and 0xFF)
        for (i in 25 downTo 18) {
            out[i] = ALPHABET[(lo and 0x1F).toInt()]
            lo = lo ushr 5
        }
        // Bridge: bottom 5 bits at position 17 are the bottom of `hi` joined
        // with the residual bits of `lo`. After the loop above lo has 0 bits
        // left (40 - 8*5 = 0), so position 17..10 is purely from `hi`.
        for (i in 17 downTo 10) {
            out[i] = ALPHABET[(hi and 0x1F).toInt()]
            hi = hi ushr 5
        }
        return String(out)
    }
}
