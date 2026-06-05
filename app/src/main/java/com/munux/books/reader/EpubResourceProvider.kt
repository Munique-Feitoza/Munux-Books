package com.munux.books.reader

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

class EpubResourceProvider(file: File) : Closeable {
    private val zip: ZipFile = ZipFile(file)
    private val lock = Any()

    fun read(path: String): ByteArray? = synchronized(lock) {
        val entry = zip.getEntry(path) ?: return null
        zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() {
        synchronized(lock) { runCatching { zip.close() } }
    }

    companion object {
        fun mimeTypeFor(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "html", "htm", "xhtml" -> "text/html"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                else -> "application/octet-stream"
            }
        }
    }
}
