package com.example.mrubyime

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * SAF entry point. **One root that opens straight into
 * `/Android/data/<pkg>/files`** — the same folder a file manager (MT Manager,
 * Material Files, the system Files app) sees on disk — so there is exactly one
 * place to edit and no doubt about which copy the IME reads:
 *
 *     Android/data/com.example.mrubyime/files/
 *       layouts/keyboard.rb     keyboard DSL (empty dir -> built-in layout)
 *       fonts/<name>.ttf        user fonts
 *       dicts/active.cin        user table (mirror of the imported one)
 *       sounds/<name>.ogg       key sounds
 *
 * The IME still keeps its working copies in `filesDir/…` (it must — that is
 * what it reads). A write made here is pulled into the working copies by the
 * `*.sync()` calls that already run on every keyboard show, exactly like an
 * edit done directly in Android/data. Create/delete/rename are mirrored
 * immediately, so the newer-wins sync can never resurrect a deleted file.
 *
 * Document ids are `f:<path relative to the root>` with `/` separators, so
 * nesting works at any depth; `canonicalPath` + prefix check rejects `..`
 * escapes. When external storage is unavailable the root falls back to
 * `filesDir` instead of disappearing.
 */
class LayoutDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY_SUFFIX = ".documents"

        /** The single root's document id. */
        private const val ROOT_ID = "root"

        /** Prefix of every non-root document id. */
        private const val DOC_PREFIX = "f:"

        private const val MIME_RUBY = "text/x-ruby"
        private const val MIME_TEXT = "text/plain"
        private const val MIME_FALLBACK = "application/octet-stream"

        private const val TAG = "MrubyimeLayout"

        /** Folders pre-created under the root so it is never empty. */
        private val EDIT_DIRS = listOf("layouts", "fonts", "dicts", "sounds")

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY
        )
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "LayoutDocumentsProvider.onCreate")
        val ctx = context ?: return false
        // Import whatever an outside editor (file manager, SAF write into
        // Android/data) changed while we were not running, BEFORE seeding:
        // seeding rewrites both the internal copy and the Android/data mirror,
        // so doing it first would throw a pending external edit away.
        runCatching { KeyboardLayoutFiles.sync(ctx) }
        runCatching { DictionaryRepository.sync(ctx) }
        // Seed the editable layout and make sure every editable folder
        // exists, so the root always shows something useful.
        KeyboardLayoutFiles.exportBuiltin(ctx)
        // A copy of the shipped table so `dicts/` is browsable/editable too.
        DictionaryRepository.seedBuiltinCopy(ctx)
        FontStore.ensureDirs(ctx)
        val root = rootDir()
        for (name in EDIT_DIRS) {
            runCatching { File(root, name).mkdirs() }
        }
        Log.i(TAG, "root=${root.absolutePath}")
        return true
    }

    // ------------------------------------------------------------- root / ids

    /**
     * The folder the root opens into: `Android/data/<pkg>/files`.
     * Falls back to the internal files dir when external storage is unavailable
     * (unmounted / removed user) so the SAF root never disappears.
     */
    private fun rootDir(): File {
        val ctx = context!!
        val ext = try {
            ctx.getExternalFilesDir(null)
        } catch (t: Throwable) {
            Log.e(TAG, "getExternalFilesDir failed", t)
            null
        }
        return ext ?: ctx.filesDir
    }

    /** Document id -> File (canonical, guaranteed to live under the root). */
    private fun fileFor(documentId: String): File? {
        val root = try {
            rootDir().canonicalFile
        } catch (t: Throwable) {
            Log.e(TAG, "root canonical failed", t)
            return null
        }
        if (documentId == ROOT_ID) return root
        if (!documentId.startsWith(DOC_PREFIX)) return null
        val rel = documentId.removePrefix(DOC_PREFIX)
        if (rel.isEmpty()) return null
        val file = try {
            File(root, rel).canonicalFile
        } catch (t: Throwable) {
            null
        } ?: return null
        // Reject anything that escaped the root through `..` or a symlink.
        if (!file.path.startsWith(root.path + File.separator)) return null
        return file
    }

    private fun docId(file: File): String {
        val root = try {
            rootDir().canonicalFile
        } catch (t: Throwable) {
            return ROOT_ID
        }
        val target = try {
            file.canonicalFile
        } catch (t: Throwable) {
            file.absoluteFile
        }
        if (target == root) return ROOT_ID
        if (!target.path.startsWith(root.path + File.separator)) return ROOT_ID
        val rel = target.path.substring(root.path.length + 1)
        return DOC_PREFIX + rel.replace(File.separatorChar, '/')
    }

    /** `layouts/keyboard.rb`-style path of [file] under the root ("" for the root). */
    private fun relPath(file: File): String {
        val id = docId(file)
        return if (id == ROOT_ID) "" else id.removePrefix(DOC_PREFIX)
    }

    /** The IME's working copy of a SAF file: `filesDir/<same relative path>`. */
    private fun workingCopy(file: File): File? {
        val rel = relPath(file)
        if (rel.isEmpty()) return null
        return File(context!!.filesDir, rel)
    }

    // ------------------------------------------------------------------ roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context!!
        val root = rootDir()
        val summary = if (root == ctx.filesDir) {
            root.absolutePath
        } else {
            "Android/data/${ctx.packageName}/files"
        }
        Log.i(TAG, "queryRoots -> $ROOT_ID ($summary)")
        val res = ctx.resources
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_TITLE, res.getString(R.string.documents_root_title))
            add(Root.COLUMN_SUMMARY, summary)
            add(Root.COLUMN_ICON, R.mipmap.ic_keyboard_logo)
            // COLUMN_MIME_TYPES stays unset: a declared list makes DocumentsUI
            // drop the root from `*/*` pickers ("unsupported content types").
            //
            // FLAG_SUPPORTS_IS_CHILD is REQUIRED for ACTION_OPEN_DOCUMENT_TREE:
            // DocumentsUI's ProvidersAccess filters with
            //   `state.action == ACTION_OPEN_TREE && !root.supportsChildren()`
            // so without it the root only shows in *file* pickers and a file
            // manager (MT Manager) cannot pick the folder to get a grant.
            // We do implement `isChildDocument()`, so the flag is truthful.
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or
                    Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(Root.COLUMN_AVAILABLE_BYTES, root.usableSpace)
        }
        Log.i(TAG, "queryRoots rows=${cursor.count}")
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId) ?: return false
        val child = fileFor(documentId) ?: return false
        return child.path.startsWith(parent.path + File.separator)
    }

    // -------------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        includeFile(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val dir = fileFor(parentDocumentId) ?: return cursor
        if (!dir.isDirectory) return cursor
        dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { includeFile(cursor, it) }
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        val file = fileFor(documentId) ?: return MIME_FALLBACK
        return if (file.isDirectory) Document.MIME_TYPE_DIR else mimeFor(file.name)
    }

    private fun includeFile(cursor: MatrixCursor, file: File) {
        val isDir = file.isDirectory
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId(file))
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(
                Document.COLUMN_MIME_TYPE,
                if (isDir) Document.MIME_TYPE_DIR else mimeFor(file.name)
            )
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_SIZE, if (isDir) null as Any? else file.length())
            add(
                Document.COLUMN_FLAGS,
                if (isDir) {
                    Document.FLAG_DIR_SUPPORTS_CREATE
                } else {
                    Document.FLAG_SUPPORTS_WRITE or
                        Document.FLAG_SUPPORTS_DELETE or
                        Document.FLAG_SUPPORTS_RENAME
                }
            )
            add(Document.COLUMN_SUMMARY, null as Any?)
        }
    }

    private fun mimeFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".rb") -> MIME_RUBY
            lower.endsWith(".cin") || lower.endsWith(".txt") || lower.endsWith(".json") ->
                MIME_TEXT
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".otf") -> "font/otf"
            lower.endsWith(".ogg") -> "audio/ogg"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(lower))
                ?: MIME_FALLBACK
        }
    }

    // --------------------------------------------------------------------- io

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        if (file.isDirectory) throw FileNotFoundException(documentId)
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw IllegalArgumentException("Unsupported mode: $mode")
        }
        // Writes are picked up by the `*.sync()` calls on the next keyboard show
        // (the descriptor closes long after this method returns, so the content
        // is not visible yet — never copy it here).
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = fileFor(parentDocumentId) ?: throw FileNotFoundException(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        var name = displayName.ifBlank { "keyboard.rb" }
        if (!name.contains('.')) {
            name += when (mimeType) {
                MIME_RUBY -> ".rb"
                else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { ".$it" }
                    ?: ""
            }
        }
        if (name.contains('/') || name.contains('\\')) throw FileNotFoundException(displayName)
        val file = File(parent, name)
        try {
            if (!file.createNewFile()) throw FileNotFoundException("$name already exists")
        } catch (t: java.io.IOException) {
            throw FileNotFoundException(t.message)
        }
        return docId(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        if (file == rootDir().canonicalFile) throw FileNotFoundException(documentId)
        val copy = workingCopy(file)
        val rel = relPath(file)
        if (!file.deleteRecursively()) throw FileNotFoundException(documentId)
        // Drop the working copy too: the newer-wins sync would otherwise copy it
        // straight back and the delete would look like it did nothing.
        runCatching { copy?.deleteRecursively() }
        propagate(rel)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val file = fileFor(documentId) ?: throw FileNotFoundException(documentId)
        val root = rootDir().canonicalFile
        if (file == root) return null
        if (displayName.isBlank() ||
            displayName.contains('/') || displayName.contains('\\') ||
            displayName == "." || displayName == ".."
        ) {
            return null
        }
        val target = File(file.parentFile, displayName)
        if (target.exists()) throw FileNotFoundException("$displayName already exists")
        val copy = workingCopy(file)
        val rel = relPath(file)
        if (!file.renameTo(target)) throw FileNotFoundException("rename failed")
        // Rename the working copy as well, or the sync restores the old name.
        runCatching {
            if (copy != null && copy.exists()) {
                val copyTarget = File(copy.parentFile, displayName)
                if (!copy.renameTo(copyTarget)) {
                    copy.deleteRecursively()
                }
            }
        }
        propagate(rel)
        return docId(target)
    }

    /**
     * Keeps the IME's working copy in step after a structural change and lets
     * the IME hot-reload on the next keyboard show.
     */
    private fun propagate(rel: String) {
        val ctx = context ?: return
        when (rel.substringBefore('/')) {
            "layouts" -> runCatching { KeyboardLayoutFiles.sync(ctx) }
            "dicts" -> runCatching { DictionaryRepository.sync(ctx) }
            "fonts" -> FontStore.clear()
            else -> Unit
        }
    }
}
