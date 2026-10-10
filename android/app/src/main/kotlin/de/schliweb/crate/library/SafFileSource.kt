// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.schliweb.crate.core.DEFAULT_EXTENSIONS
import de.schliweb.crate.core.FileSource
import de.schliweb.crate.core.Folder
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.ScannedFile
import de.schliweb.crate.core.isAudioFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Lists a document tree the user picked with the system folder picker.
 * Goes through `DocumentsContract` queries directly rather than
 * `DocumentFile`, which issues one query per attribute per file and
 * turns a 2,000-file folder into minutes.
 *
 * Track paths are built from the display names on the way down,
 * starting with the tree's document id ("primary:Music/Artist/Album/01.mp3"),
 * so they read like file paths and album grouping can use them whatever
 * the provider's own ids look like.
 */
class SafFileSource(
    private val context: Context,
    private val extensions: Set<String> = DEFAULT_EXTENSIONS,
) : FileSource {
    override suspend fun scan(folder: Folder): List<ScannedFile> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder.uri)
        val files = mutableListOf<ScannedFile>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), folder.path, files)
        files.sortBy { it.path }
        files
    }

    private fun walk(tree: Uri, documentId: String, path: String, out: MutableList<ScannedFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val cursor = try {
            context.contentResolver.query(children, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw IOException("no access to $path", e)
        } ?: throw FileNotFoundException(path)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = it.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(tree, id, Paths.join(path, name), out)
                } else if (isAudioFileName(name, extensions)) {
                    out.add(
                        ScannedFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            path = Paths.join(path, name),
                            fileSize = it.getLong(3),
                            modified = it.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /** Describes the tree the picker returned: its URI, the path its
         *  tracks start with, and its name for the menu. */
        fun folder(context: Context, tree: Uri): Folder {
            val documentId = DocumentsContract.getTreeDocumentId(tree)
            var name = documentId.substringAfterLast('/').substringAfterLast(':')
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            runCatching {
                context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() }?.let { n -> name = n } }
            }
            return Folder(uri = tree.toString(), path = documentId, name = name.ifBlank { "Music" })
        }
    }
}
