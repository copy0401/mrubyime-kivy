package com.example.mrubyime

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Shared SHA-256 helpers (the engine / layout / table seeds all compare digests
 * so an untouched seeded file is never mistaken for a user edit).
 *
 * [ofFile] returns a hex digest, or `""` when the bytes cannot be read. The
 * empty string never equals a real digest, so a failed read is treated as "not
 * the seed" and nothing gets overwritten by accident.
 */
internal object Checksums {
    fun ofBytes(bytes: ByteArray): String = try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Log.e("MrubyimeChecksum", "digest failed", t)
        ""
    }

    fun ofFile(file: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        Log.e("MrubyimeChecksum", "file digest failed: ${file.name}", t)
        ""
    }
}
