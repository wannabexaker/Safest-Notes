package com.tezas.safestnotes.export

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.tezas.safestnotes.R
import com.tezas.safestnotes.security.AesGcm
import com.tezas.safestnotes.security.KeyDerivation
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs an encrypted SafestNotes payload at the end of a real MP3 file so the
 * output plays as silent audio in any media player but carries the ciphertext
 * covertly.
 *
 * On-disk layout:
 *
 *   [ silence_stub.mp3 bytes  ] ← from res/raw, ~12 KB of silent audio
 *   [ ciphertext (GCM)        ] ← AES-256-GCM, tag appended
 *   [ trailer (fixed 62 bytes)]
 *
 * Trailer (big-endian):
 *
 *   offset  size  field
 *   0       8     magic "SFSTNT01"
 *   8       1     version (currently 1)
 *   9       1     reserved (0)
 *   10     32     PBKDF2 salt
 *   42     12     GCM IV
 *   54      8     ciphertext length (unsigned, but we use positive Long)
 *
 * The caller provides a password. We derive a 32-byte key with PBKDF2 using
 * [KeyDerivation.deriveKek] and encrypt with [AesGcm]. No plaintext length is
 * leaked beyond the unavoidable ciphertext length in the trailer.
 */
object Mp3ExportCodec {

    private const val MAGIC = "SFSTNT01"
    private const val MAGIC_SIZE = 8
    const val TRAILER_SIZE = 62
    const val VERSION: Byte = 1

    class BadFileException(msg: String) : Exception(msg)
    class WrongPasswordException : Exception("Incorrect password or corrupt file")

    /**
     * Pack [payload] (plain bytes) into an MP3-shaped file written to [out].
     * The password is used only for this export; it is independent from the
     * vault master password.
     */
    fun pack(context: Context, password: CharArray, payload: ByteArray, out: OutputStream) {
        val salt = KeyDerivation.generateSalt()
        val kek = KeyDerivation.deriveKek(password, salt)
        try {
            val blob = AesGcm.encrypt(kek, payload)

            resolveCoverStream(context).use { stub ->
                stub.copyTo(out)
            }
            out.write(blob.ciphertext)
            out.write(buildTrailer(salt, blob.iv, blob.ciphertext.size.toLong()))
            out.flush()
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Read a SafestNotes-in-MP3 file from [input] (must support seeking via
     * mark/reset is *not* required — we buffer into memory). Returns the
     * decrypted payload bytes.
     *
     * @throws BadFileException if magic mismatch / truncated
     * @throws WrongPasswordException if GCM authentication fails
     */
    fun unpack(input: InputStream, password: CharArray): ByteArray {
        val all = input.readBytes()
        if (all.size < TRAILER_SIZE) {
            throw BadFileException("File too short (${all.size} bytes)")
        }
        val trailer = all.copyOfRange(all.size - TRAILER_SIZE, all.size)
        val magic = trailer.copyOfRange(0, MAGIC_SIZE).toString(Charsets.US_ASCII)
        if (magic != MAGIC) {
            throw BadFileException("Not a SafestNotes export (magic=$magic)")
        }
        val version = trailer[8]
        if (version != VERSION) {
            throw BadFileException("Unsupported version $version")
        }
        val salt = trailer.copyOfRange(10, 42)
        val iv = trailer.copyOfRange(42, 54)
        val ctLen = ByteBuffer.wrap(trailer, 54, 8).order(ByteOrder.BIG_ENDIAN).long
        if (ctLen < 0 || ctLen > all.size - TRAILER_SIZE) {
            throw BadFileException("Bad ciphertext length $ctLen")
        }
        val ctStart = (all.size - TRAILER_SIZE - ctLen.toInt())
        val ct = all.copyOfRange(ctStart, all.size - TRAILER_SIZE)

        val kek = KeyDerivation.deriveKek(password, salt)
        try {
            return AesGcm.decrypt(kek, iv, ct)
        } catch (e: Exception) {
            throw WrongPasswordException()
        } finally {
            kek.fill(0)
        }
    }

    /** Inspect a file without decrypting. Returns null if not a SafestNotes export. */
    fun probe(bytes: ByteArray): Boolean {
        if (bytes.size < TRAILER_SIZE) return false
        val magicBytes = bytes.copyOfRange(bytes.size - TRAILER_SIZE, bytes.size - TRAILER_SIZE + MAGIC_SIZE)
        return magicBytes.toString(Charsets.US_ASCII) == MAGIC
    }

    /**
     * Resolve the "cover audio" bytes that get prepended before the encrypted
     * payload. Controlled by the user via Settings → Cover audio. Falls back
     * to silent stub on any error so export never breaks because of a stale
     * custom URI.
     */
    private fun resolveCoverStream(context: Context): InputStream {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val choice = prefs.getString(PREF_COVER_AUDIO, COVER_SILENCE) ?: COVER_SILENCE
        return when (choice) {
            COVER_PINK -> context.resources.openRawResource(R.raw.cover_rain)
            COVER_CUSTOM -> {
                val uriStr = prefs.getString(PREF_COVER_AUDIO_URI, null)
                val uri = uriStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
                uri?.let {
                    runCatching { context.contentResolver.openInputStream(it) }
                        .getOrNull()
                } ?: context.resources.openRawResource(R.raw.silence_stub)
            }
            else -> context.resources.openRawResource(R.raw.silence_stub)
        }
    }

    const val PREF_COVER_AUDIO = "pref_cover_audio"
    const val PREF_COVER_AUDIO_URI = "pref_cover_audio_uri"
    const val COVER_SILENCE = "silence"
    const val COVER_PINK = "pink"
    const val COVER_CUSTOM = "custom"

    private fun buildTrailer(salt: ByteArray, iv: ByteArray, ctLen: Long): ByteArray {
        require(salt.size == 32) { "salt must be 32 bytes" }
        require(iv.size == 12) { "iv must be 12 bytes" }
        val buf = ByteBuffer.allocate(TRAILER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII)) // 8
        buf.put(VERSION)                              // 1
        buf.put(0.toByte())                           // 1 reserved
        buf.put(salt)                                 // 32
        buf.put(iv)                                   // 12
        buf.putLong(ctLen)                            // 8
        return buf.array()
    }
}
