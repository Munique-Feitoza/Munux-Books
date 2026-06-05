package com.munux.books.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.munux.books.data.Book
import com.munux.books.data.BookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object BookImporter {

    suspend fun import(context: Context, uri: Uri): Book = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri) ?: "livro"
        val format = detectFormat(displayName)

        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val safeName = "${UUID.randomUUID()}_${displayName.sanitize()}"
        val outFile = File(booksDir, safeName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Não foi possível abrir o arquivo")

        val (title, author) = when (format) {
            BookFormat.EPUB -> {
                val meta = runCatching { EpubParser.readMetadata(outFile) }.getOrNull()
                (meta?.title ?: displayName.substringBeforeLast('.')) to meta?.author
            }
            else -> displayName.substringBeforeLast('.') to null
        }

        Book(
            title = title.ifBlank { displayName },
            author = author,
            filePath = outFile.absolutePath,
            format = format
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun detectFormat(name: String): BookFormat = when {
        name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
        name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
        else -> BookFormat.UNKNOWN
    }

    private fun String.sanitize(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
