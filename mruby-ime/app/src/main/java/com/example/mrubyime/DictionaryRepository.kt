package com.example.mrubyime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Source of the active `.cin` table.
 *
 * Layout:
 *   assets/mrubyime.cin              shipped built-in table (the floor)
 *   assets/dicts/<name>.cin          optional additional built-ins
 *   filesDir/dicts/mrubyime.cin      editable copy of the built-in (canonical)
 *   Android/data/<pkg>/files/dicts/mrubyime.cin   external-edit mirror
 *   filesDir/dicts/active.cin        user-imported table (canonical)
 *   Android/data/<pkg>/files/dicts/active.cin     external-edit mirror
 *
 * The built-in table behaves exactly like `engine.rb` / `keyboard.rb`: a copy
 * is seeded next to the other editable files so a file manager (or MT Manager)
 * can read and **edit it in place**. While the copy is untouched the shipped
 * asset is used as-is; an edited copy is loaded instead, and deleting it brings
 * the shipped table back. A copy that is not a sane `.cin` is ignored, so it can
 * never brick the IME.
 *
 * The selected table is stored in the existing `mrubyime_settings` prefs as
 * `dict_id` (`builtin:mrubyime`, `builtin:<name>`, `user:active`).
 */
object DictionaryRepository {
    private const val TAG = "MrubyimeDict"
    const val PREFS = "mrubyime_settings"
    const val KEY_DICT_ID = "dict_id"

    const val DEFAULT_ID = "builtin:mrubyime"
    const val USER_ID = "user:active"

    private const val BUILTIN_DIR = "dicts"
    private const val LEGACY_ASSET = "mrubyime.cin"
    private const val USER_DIR = "dicts"
    private const val USER_NAME = "active.cin"
    private const val BUILTIN_COPY_NAME = "mrubyime.cin"

    /** Fingerprint of the seeded built-in copy, to tell seeds from user edits. */
    private const val KEY_COPY_HASH = "builtin_copy_hash"
    private const val KEY_COPY_SIZE = "builtin_copy_size"

    data class Entry(val id: String, val name: String, val builtin: Boolean)

    /** Built-in tables plus the user file, in a stable order. */
    fun list(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        out += Entry(DEFAULT_ID, builtinLabel(context), true)

        val names = try {
            context.assets.list(BUILTIN_DIR)
                ?.filter { it.endsWith(".cin", ignoreCase = true) }
                ?.sorted()
                .orEmpty()
        } catch (t: Throwable) {
            Log.e(TAG, "assets.list($BUILTIN_DIR) failed", t)
            emptyList()
        }
        for (fileName in names) {
            val base = fileName.substringBeforeLast('.')
            val id = "builtin:$base"
            if (id != DEFAULT_ID) out += Entry(id, base, true)
        }

        if (userFile(context).exists()) out += Entry(USER_ID, "使用者碼表 active.cin", false)
        return out
    }

    /** `%cname` from the built-in asset (reads only the first few KB). */
    private fun builtinLabel(context: Context): String = try {
        val head = context.assets.open(LEGACY_ASSET).use { input ->
            val buf = ByteArray(4096)
            val n = input.read(buf)
            String(buf, 0, if (n > 0) n else 0, Charsets.UTF_8)
        }
        val cname = head.lineSequence()
            .firstOrNull { it.startsWith("%cname") }
            ?.removePrefix("%cname")?.trim()
        // Say so when `dicts/mrubyime.cin` has been edited, so the Settings list
        // makes it obvious that the built-in entry is no longer the shipped one.
        val suffix = if (builtinCopyEdited(context)) "（內建．已修改）" else "（內建）"
        if (cname.isNullOrEmpty()) "內建碼表$suffix" else "$cname$suffix"
    } catch (t: Throwable) {
        Log.e(TAG, "builtinLabel failed", t)
        "內建碼表"
    }

    /** The shipped built-in bytes, ignoring the editable copy. */
    fun assetBytes(context: Context): ByteArray? = try {
        context.assets.open(LEGACY_ASSET).use { it.readBytes() }
    } catch (t: Throwable) {
        Log.e(TAG, "asset $LEGACY_ASSET read failed", t)
        null
    }

    fun read(context: Context, id: String): ByteArray? = try {
        when {
            id == DEFAULT_ID -> {
                // The editable copy (`dicts/mrubyime.cin`, mirrored into
                // Android/data) wins when it is present AND sane; otherwise the
                // shipped asset is used, so a truncated/garbage copy is harmless.
                val copy = builtinCopyFile(context)
                if (looksLikeCin(copy)) copy.readBytes() else assetBytes(context)
            }
            id.startsWith("builtin:") -> {
                val base = id.removePrefix("builtin:")
                val fileName = context.assets.list(BUILTIN_DIR)
                    ?.firstOrNull { it.equals("$base.cin", ignoreCase = true) }
                    ?: "$base.cin"
                context.assets.open("$BUILTIN_DIR/$fileName").use { it.readBytes() }
            }
            id == USER_ID -> userFile(context).takeIf { it.exists() }?.readBytes()
            else -> null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "read($id) failed", t)
        null
    }

    fun activeId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DICT_ID, DEFAULT_ID) ?: DEFAULT_ID

    fun setActive(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DICT_ID, id).apply()
    }

    /**
     * Loads the selected table. Falls back to the built-in 形碼 table when
     * the user file was deleted or the asset id no longer exists.
     */
    fun loadActive(context: Context): Pair<String, ByteArray>? {
        sync(context)
        val id = activeId(context)
        read(context, id)?.let { return id to it }
        if (id != DEFAULT_ID) {
            read(context, DEFAULT_ID)?.let { return DEFAULT_ID to it }
        }
        return null
    }

    fun userFile(context: Context): File =
        File(context.filesDir, "$USER_DIR/$USER_NAME")

    fun mirrorFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "$USER_DIR/$USER_NAME") }

    /** Editable copy of the built-in table (`filesDir/dicts/mrubyime.cin`). */
    fun builtinCopyFile(context: Context): File =
        File(context.filesDir, "$USER_DIR/$BUILTIN_COPY_NAME")

    /** The Android/data copy a file manager (MT Manager) reads and writes. */
    fun builtinCopyMirror(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "$USER_DIR/$BUILTIN_COPY_NAME") }

    /** Reconciles both file pairs, newer wins. */
    fun sync(context: Context) {
        syncPair(userFile(context), mirrorFile(context))
        syncPair(builtinCopyFile(context), builtinCopyMirror(context))
    }

    private fun syncPair(internal: File, mirror: File?) {
        if (mirror == null) return
        try {
            val internalOk = internal.isFile && internal.length() > 0
            val mirrorOk = mirror.isFile && mirror.length() > 0
            when {
                mirrorOk && (!internalOk || mirror.lastModified() - internal.lastModified() > 1000L) -> {
                    internal.parentFile?.mkdirs()
                    mirror.copyTo(internal, overwrite = true)
                    internal.setLastModified(mirror.lastModified())
                    Log.i(TAG, "imported ${mirror.name} from Android/data")
                }
                internalOk && (!mirrorOk || internal.lastModified() - mirror.lastModified() > 1000L) -> {
                    mirror.parentFile?.mkdirs()
                    internal.copyTo(mirror, overwrite = true)
                    mirror.setLastModified(internal.lastModified())
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sync failed: ${mirror.name}", t)
        }
    }

    /**
     * Seeds `dicts/mrubyime.cin` (internal + Android/data) from the shipped
     * asset so the built-in table can be inspected and edited in place.
     *
     * Same rule as `engine.rb` / `keyboard.rb`: an untouched copy (hash equals
     * the recorded seed) is refreshed when the APK ships a new table, while a
     * copy the user edited is never overwritten. Runs off the main thread.
     */
    fun seedBuiltinCopy(context: Context) {
        val src = assetBytes(context) ?: return
        val dst = builtinCopyFile(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recordedHash = prefs.getString(KEY_COPY_HASH, null)
        val recordedSize = prefs.getLong(KEY_COPY_SIZE, -1L)
        try {
            if (dst.isFile && dst.length() > 0) {
                if (recordedHash == null || recordedHash != Checksums.ofFile(dst)) {
                    Log.i(TAG, "builtin copy was edited by the user, leaving it alone")
                    return
                }
                if (recordedSize == src.size.toLong() && recordedHash == Checksums.ofBytes(src)) {
                    return // already the shipped table
                }
            }
            val hash = Checksums.ofBytes(src)
            writeAtomic(dst, src)
            builtinCopyMirror(context)?.let { m ->
                runCatching {
                    writeAtomic(m, src)
                    m.setLastModified(dst.lastModified())
                }
            }
            prefs.edit()
                .putString(KEY_COPY_HASH, hash)
                .putLong(KEY_COPY_SIZE, src.size.toLong())
                .apply()
            Log.i(TAG, "seeded builtin copy bytes=${src.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "seedBuiltinCopy failed", t)
        }
    }

    /** Temp write + rename so the IME never reads a half-written table. */
    fun install(context: Context, bytes: ByteArray): Boolean = try {
        if (bytes.isEmpty()) {
            Log.e(TAG, "install rejected: empty bytes")
            false
        } else {
            val target = builtinCopyFile(context)
            writeAtomic(target, bytes)
            builtinCopyMirror(context)?.let { m ->
                runCatching {
                    writeAtomic(m, bytes)
                    m.setLastModified(target.lastModified())
                }
            }
            // The new file is no longer "the seed we wrote": record its digest
            // so it can never be mistaken for an untouched copy and refreshed.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_COPY_HASH, Checksums.ofBytes(bytes)).apply()
            Log.i(TAG, "installed dicts/mrubyime.cin bytes=${bytes.size}")
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "install failed", t)
        false
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }

    /**
     * Cheap sanity check for a user-supplied table: the header must be present
     * in the first 64 KB. Anything else (truncated copy, wrong file) is ignored
     * so [read] falls back to the shipped asset instead of loading garbage.
     */
    private fun looksLikeCin(file: File): Boolean = try {
        if (!file.isFile || file.length() < 1024L) {
            false
        } else {
            val buf = ByteArray(64 * 1024)
            val n = file.inputStream().use { it.read(buf) }
            val head = String(buf, 0, if (n > 0) n else 0, Charsets.UTF_8)
            head.contains("%chardef") || head.contains("%cname") || head.contains("%ename")
        }
    } catch (t: Throwable) {
        Log.e(TAG, "looksLikeCin failed: ${file.name}", t)
        false
    }

    /** Validates and stores a user table, then makes it active. */
    fun saveUser(context: Context, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            val f = userFile(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.writeBytes(bytes)
                tmp.delete()
            }
            mirrorFile(context)?.let { m ->
                m.parentFile?.mkdirs()
                m.writeBytes(bytes)
                m.setLastModified(f.lastModified())
            }
            setActive(context, USER_ID)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "saveUser failed", t)
            false
        }
    }

    /**
     * 「恢復內建碼表」: drops the imported user table *and* an edited copy of the
     * built-in table, so the shipped `.cin` comes back. The copy is re-seeded
     * from the APK on the next start.
     */
    fun restoreBuiltin(context: Context) {
        userFile(context).delete()
        mirrorFile(context)?.delete()
        builtinCopyFile(context).delete()
        builtinCopyMirror(context)?.delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_COPY_HASH).remove(KEY_COPY_SIZE).apply()
        setActive(context, DEFAULT_ID)
    }

    /** True when `dicts/mrubyime.cin` exists and differs from the shipped table. */
    fun builtinCopyEdited(context: Context): Boolean {
        val copy = builtinCopyFile(context)
        if (!copy.isFile) return false
        val recorded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COPY_HASH, null) ?: return true
        return recorded != Checksums.ofFile(copy)
    }

    /**
     * Cheap change token for the active table. Optional built-ins are immutable
     * at runtime, so the id alone is enough; the user table and the editable
     * built-in copy include size + mtime so an external edit (Settings, a file
     * manager writing Android/data) is picked up on the next keyboard show.
     */
    fun signature(context: Context): String {
        sync(context)
        val id = activeId(context)
        if (id == DEFAULT_ID) {
            val copy = builtinCopyFile(context)
            return if (copy.isFile) "$id:${copy.length()}:${copy.lastModified()}" else id
        }
        if (id != USER_ID) return id
        val f = userFile(context)
        return "$id:${f.length()}:${f.lastModified()}"
    }
}
