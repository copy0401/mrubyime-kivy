package com.example.mrubyime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * User-editable keyboard layout (P-1).
 *
 * `filesDir/layouts/keyboard.rb` is the single source of truth: a plain Ruby DSL
 * workbook (the same `Keyboard.layout` DSL as the built-in
 * `assets/keyboard_layout.rb`) written in UTF-8. The IME evaluates it in the JNI
 * sandbox when the input view is created and on `onStartInputView`, so an edit
 * takes effect the next time the keyboard is shown.
 *
 * Access paths (all reach the same content):
 *  - file managers with Android/data access:
 *      Android/data/com.example.mrubyime/files/layouts/keyboard.rb
 *  - SAF / share: content://com.example.mrubyime.layouts/keyboard.rb (LayoutFileProvider)
 *  - root / Shizuku: /data/data/com.example.mrubyime/files/layouts/keyboard.rb
 *
 * The external copy is a timestamped mirror; on keyboard show the newer file wins
 * (see [sync]).
 */
object KeyboardLayoutFiles {

    const val TAG = "MrubyimeLayout"

    /** Same cap as the JNI sandbox (MAX_LAYOUT_SOURCE_BYTES in mrubyime_jni.c). */
    const val MAX_BYTES = 64 * 1024

    private const val FILE_NAME = "keyboard.rb"

    /** Marks the seeded keyboard.rb as "an untouched copy of the built-in". */
    private const val KEY_SEED_HASH = "builtin_seed_sha256"

    fun layoutDir(context: Context): File =
        File(context.filesDir, "layouts").also { if (!it.exists()) it.mkdirs() }

    /** The only file the IME reads. Missing means the built-in layout. */
    fun keyboardFile(context: Context): File = File(layoutDir(context), FILE_NAME)

    /** Android/data/<pkg>/files/layouts/ -- reachable by Android/data file managers. */
    fun mirrorDir(context: Context): File? = try {
        val base = context.getExternalFilesDir(null) ?: return null
        val dir = File(base, "layouts")
        if (dir.exists() || dir.mkdirs()) dir else null
    } catch (t: Throwable) {
        Log.e(TAG, "mirrorDir failed", t)
        null
    }

    fun mirrorFile(context: Context): File? =
        mirrorDir(context)?.let { File(it, FILE_NAME) }

    fun hasUserLayout(context: Context): Boolean = keyboardFile(context).isFile

    /** The built-in DSL source, for export. */
    fun builtinSource(context: Context): String? = try {
        context.assets.open("keyboard_layout.rb").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        Log.e(TAG, "builtin source read failed", t)
        null
    }

    fun readKeyboard(context: Context): String? = try {
        val file = keyboardFile(context)
        if (file.isFile && file.length() in 1..MAX_BYTES.toLong()) {
            file.readText(Charsets.UTF_8)
        } else {
            null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "keyboard read failed", t)
        null
    }

    /**
     * Writes [source] as keyboard.rb and mirrors it to Android/data.
     * The caller is expected to have validated it.
     */
    fun install(context: Context, source: String): Boolean = try {
        val bytes = source.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
            Log.e(TAG, "install rejected: ${bytes.size} bytes")
            false
        } else {
            keyboardFile(context).writeBytes(bytes)
            mirrorFile(context)?.writeBytes(bytes)
            Log.i(TAG, "installed keyboard.rb bytes=${bytes.size}")
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "install failed", t)
        false
    }

    /** Drops keyboard.rb; the IME falls back to the built-in layout. */
    fun restoreBuiltin(context: Context): Int {
        var removed = 0
        for (file in listOfNotNull(keyboardFile(context), mirrorFile(context))) {
            try {
                if (file.isFile && file.delete()) removed++
            } catch (t: Throwable) {
                Log.e(TAG, "delete failed: ${file.absolutePath}", t)
            }
        }
        Log.i(TAG, "restored builtin layout (removed $removed)")
        return removed
    }

    /**
     * Writes the built-in DSL as keyboard.rb (+ mirror) the first time so it can be
     * edited, and **keeps an untouched copy in step with the shipped built-in**.
     *
     * A layout the user actually edited is never touched: the seed's hash is
     * recorded when it is written, and only a file whose hash still matches that
     * recorded seed is replaced (i.e. "revert to the current default"). Without
     * this, shipping a new built-in would silently lose to the stale clone the
     * provider seeded on a previous install.
     */
    fun exportBuiltin(context: Context): Boolean {
        // Import a newer Android/data copy before deciding: `install()` writes
        // both copies, so seeding first would discard an edit made in
        // `Android/data/<pkg>/files/layouts/keyboard.rb` while the canonical
        // file was still the untouched seed.
        sync(context)
        if (!hasUserLayout(context)) {
            val src = builtinSource(context) ?: return false
            val ok = install(context, src)
            if (ok) prefs(context).edit().putString(KEY_SEED_HASH, sha256(src)).apply()
            return ok
        }
        val kb = keyboardFile(context)
        val stored = prefs(context).getString(KEY_SEED_HASH, null) ?: return false
        val text = try {
            kb.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "seed refresh read failed", t)
            return false
        }
        if (sha256(text) != stored) return false // user layout -> leave it alone
        val src = builtinSource(context) ?: return false
        val builtinHash = sha256(src)
        if (builtinHash == stored) return false // already the current default
        val ok = install(context, src)
        if (ok) {
            prefs(context).edit().putString(KEY_SEED_HASH, builtinHash).apply()
            Log.i(TAG, "refreshed untouched builtin seed keyboard.rb")
        }
        return ok
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("mrubyime_layout", Context.MODE_PRIVATE)

    private fun sha256(text: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            // Never fatal: a failed hash just means "cannot prove it is untouched".
            Log.e(TAG, "sha256 failed", t)
            ""
        }
    }

    /**
     * Keeps Android/data/layouts/keyboard.rb in step with files/layouts/keyboard.rb.
     * The newer file wins:
     *  - mirror exists (created by an Android/data file manager) or is newer than
     *    keyboard.rb -> import it (validated first),
     *  - otherwise (missing/stale) -> write the mirror from keyboard.rb.
     *
     * An invalid mirror is left untouched (so the author can fix it) and the IME
     * keeps using the last good layout / the built-in one.
     */
    fun sync(context: Context) {
        // One-time migration: the layout file used to be named active.rb; carry
        // an existing user layout over to keyboard.rb so a rename doesn't lose it.
        migrateActiveToKeyboard(context)
        val kb = keyboardFile(context)
        val mirror = mirrorFile(context) ?: return
        try {
            val kbOk = kb.isFile && kb.length() in 1..MAX_BYTES.toLong()
            val mirrorOk = mirror.isFile && mirror.length() in 1..MAX_BYTES.toLong()
            when {
                !kbOk && !mirrorOk -> return
                !kbOk -> importMirror(kb, mirror)
                !mirrorOk -> copy(kb, mirror)
                else -> {
                    val kbTs = kb.lastModified()
                    val mirrorTs = mirror.lastModified()
                    if (mirrorTs - kbTs > 1000L) importMirror(kb, mirror)
                    else if (mirrorTs < kbTs) copy(kb, mirror)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sync failed", t)
        }
    }

    /** Validates the mirror first so a broken edit can never take the keyboard down. */
    private fun importMirror(kb: File, mirror: File) {
        val text = mirror.readText(Charsets.UTF_8)
        val parsed = KeyboardLayoutRepository.fromSource(
            MrubyimeEngineDelegateImpl(),
            text,
            "mode=chinese\ntheme=light\n"
        )
        if (parsed == null) {
            Log.w(TAG, "mirror skipped: DSL did not produce a layout")
            return
        }
        kb.writeBytes(text.toByteArray(Charsets.UTF_8))
        Log.i(TAG, "imported mirror keyboard.rb bytes=${kb.length()}")
    }

    private fun copy(from: File, to: File) {
        to.writeBytes(from.readBytes())
        Log.i(TAG, "mirrored keyboard.rb bytes=${to.length()}")
    }

    /**
     * Renames a leftover `active.rb` (the pre-rename name) to `keyboard.rb`.
     * Only acts when the canonical keyboard.rb is missing, so it never clobbers
     * a newer file. Also drops an untouched seed copy if one lingers.
     */
    private fun migrateActiveToKeyboard(context: Context) {
        try {
            val old = File(layoutDir(context), "active.rb")
            val kb = keyboardFile(context)
            if (old.isFile && !kb.exists()) {
                if (old.renameTo(kb)) {
                    Log.i(TAG, "migrated active.rb -> keyboard.rb")
                } else {
                    kb.writeBytes(old.readBytes())
                    old.delete()
                }
            }
            // Mirror migration (Android/data name changed too).
            val oldMirror = mirrorDir(context)?.let { File(it, "active.rb") }
            val kbMirror = mirrorFile(context)
            if (oldMirror != null && oldMirror.isFile &&
                (kbMirror == null || !kbMirror.exists())
            ) {
                if (kbMirror != null) {
                    kbMirror.writeBytes(oldMirror.readBytes())
                }
                oldMirror.delete()
                Log.i(TAG, "migrated mirror active.rb -> keyboard.rb")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "migrate active->keyboard failed", t)
        }
    }

    /** The effective user source: seed/refresh the default, sync, then read. */
    fun keyboardSource(context: Context): String? {
        exportBuiltin(context)
        sync(context)
        return readKeyboard(context)
    }

    /**
     * Pushes the canonical file to the Android/data mirror after an outside write
     * (DocumentsProvider). Unconditional so a fresh SAF write always wins.
     */
    fun publishKeyboard(context: Context) {
        try {
            val kb = keyboardFile(context)
            val mirror = mirrorFile(context) ?: return
            if (kb.isFile && kb.length() in 1..MAX_BYTES.toLong()) {
                mirror.writeBytes(kb.readBytes())
                Log.i(TAG, "published keyboard.rb to mirror bytes=${mirror.length()}")
            } else if (mirror.isFile) {
                mirror.delete()
                Log.i(TAG, "cleared mirror (no keyboard.rb)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "publishKeyboard failed", t)
        }
    }

    /** Cheap change detector so the IME rebuilds only when the file actually moved. */
    fun sourceSignature(context: Context): String {
        val file = keyboardFile(context)
        return if (file.isFile) "user:${file.lastModified()}:${file.length()}" else "builtin"
    }

    /** Path shown on the settings screen. */
    fun describe(context: Context): String = try {
        (mirrorDir(context) ?: layoutDir(context)).absolutePath
    } catch (t: Throwable) {
        layoutDir(context).absolutePath
    }
}
