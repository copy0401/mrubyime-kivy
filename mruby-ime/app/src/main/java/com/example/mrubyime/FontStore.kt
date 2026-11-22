package com.example.mrubyime

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

/**
 * Loads font files for the DSL `font: { family: "…" }` option, shared by the
 * buffer, the candidate chips and the key labels.
 *
 * A `family` is a file name without or with an extension (`NotoSansCJK` or
 * `NotoSansCJK.ttf`). Lookup order:
 *  1. `assets/fonts/<name>` (fonts shipped inside the APK)
 *  2. `filesDir/fonts/<name>` (app-private, reachable with root/run-as)
 *  3. `Android/data/<pkg>/files/fonts/<name>` (user-visible mirror)
 *  4. an absolute path when the name starts with `/`
 *
 * A missing family is cached as `null` so a typo does not touch the disk on
 * every render; the caller then keeps the system typeface. [clear] drops the
 * cache so a font copied in after start-up is found on the next layout build.
 */
object FontStore {
    private val cache = mutableMapOf<String, Typeface?>()

    /** Creates `filesDir/fonts` and the Android/data mirror so users can drop files in. */
    fun ensureDirs(context: Context) {
        runCatching { File(context.filesDir, "fonts").mkdirs() }
        runCatching { context.getExternalFilesDir(null)?.let { File(it, "fonts").mkdirs() } }
    }

    /** Resolved typeface for `family`, or `null` to use the system default. */
    fun typefaceFor(context: Context, family: String?): Typeface? {
        val name = family?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (cache.containsKey(name)) return cache[name]
        val loaded = load(context, name)
        cache[name] = loaded
        if (loaded == null) {
            Log.i("MrubyimeFont", "font '$name' not found; using the system typeface")
        }
        return loaded
    }

    /** Drops cached lookups (call when the layout is rebuilt). */
    fun clear() {
        cache.clear()
    }

    private fun load(context: Context, name: String): Typeface? {
        if (name.startsWith("/")) {
            return runCatching { Typeface.createFromFile(name) }.getOrNull()
        }
        val names = if (name.endsWith(".ttf", true) || name.endsWith(".otf", true)) {
            listOf(name)
        } else {
            listOf(name, "$name.ttf", "$name.otf")
        }
        for (candidate in names) {
            runCatching { Typeface.createFromAsset(context.assets, "fonts/$candidate") }
                .getOrNull()?.let { return it }
        }
        val dirs = listOfNotNull(
            File(context.filesDir, "fonts"),
            context.getExternalFilesDir(null)?.let { File(it, "fonts") }
        )
        for (candidate in names) {
            for (dir in dirs) {
                val file = File(dir, candidate)
                if (!file.isFile) continue
                runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { return it }
            }
        }
        return null
    }
}
