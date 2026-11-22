package com.example.mrubyime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Minimal replacement for `androidx.core.content.FileProvider` (dropping the
 * last androidx dependency): serves one app-owned file to an external editor
 * through `content://<pkg>.layouts/<absolute path>`.
 *
 * Only files under `filesDir` / `getExternalFilesDir(null)` are served, so the
 * authority can never be used to read arbitrary storage. The manifest sets
 * `exported=false` + `grantUriPermissions=true`, so access is per-URI-granted
 * by the ACTION_VIEW intent from the settings screen.
 */
class LayoutFileProvider : ContentProvider() {

    companion object {
        /** `content://<authority>/<absolute path of the served file>`. */
        fun uriFor(authority: String, file: File): Uri =
            Uri.parse("content://$authority/${file.absolutePath}")

        private fun mimeOf(name: String): String = when (name.substringAfterLast('.')) {
            "rb" -> "text/x-ruby"
            "cin" -> "text/x-cin"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

        /** True when [file] stays inside one of this app's private roots. */
        private fun inAppRoot(file: File, context: android.content.Context): Boolean {
            val canonical = try { file.canonicalFile } catch (t: Throwable) { return false }
            val roots = listOfNotNull(
                context.filesDir?.canonicalFile,
                context.getExternalFilesDir(null)?.canonicalFile
            )
            return roots.any { root ->
                canonical.path == root.path || canonical.path.startsWith(root.path + "/")
            }
        }
    }

    private lateinit var authority: String

    override fun onCreate(): Boolean {
        authority = context?.let { "$it.packageName.layouts" } ?: return false
        return true
    }

    private fun resolve(uri: Uri): File? {
        val path = uri.path ?: return null
        if (!path.startsWith("/")) return null
        val file = File(path)
        val ctx = context ?: return null
        return if (inAppRoot(file, ctx) && file.isFile) file else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        val write = mode.contains('w') || mode.contains('+')
        val mask = if (write) {
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, mask)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = MatrixCursor(cols)
        row.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return row
    }

    override fun getType(uri: Uri): String = mimeOf(uri.lastPathSegment ?: "")

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
