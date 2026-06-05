package com.munux.books.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfTextExtractor {

    suspend fun extractPages(file: File): List<String> = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { doc ->
            val total = doc.numberOfPages
            val stripper = PDFTextStripper()
            val pages = ArrayList<String>(total)
            for (i in 1..total) {
                stripper.startPage = i
                stripper.endPage = i
                val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                pages.add(text.trim())
            }
            pages
        }
    }

    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { it.numberOfPages }
    }
}
