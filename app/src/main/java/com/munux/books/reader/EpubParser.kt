package com.munux.books.reader

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {

    data class Metadata(val title: String, val author: String?)

    data class Chapter(
        val href: String,
        val html: String,
        val mediaType: String
    )

    data class EpubBook(
        val metadata: Metadata,
        val chapters: List<Chapter>,
        val resources: Map<String, ZipResourceInfo>
    )

    data class ZipResourceInfo(val path: String, val mediaType: String)

    fun readMetadata(file: File): Metadata = ZipFile(file).use { zip ->
        val opfPath = findOpfPath(zip) ?: return Metadata(file.nameWithoutExtension, null)
        val opf = parseXml(zip, opfPath) ?: return Metadata(file.nameWithoutExtension, null)
        val title = opf.firstTag("dc:title", "title") ?: file.nameWithoutExtension
        val author = opf.firstTag("dc:creator", "creator")
        Metadata(title, author)
    }

    fun read(file: File): EpubBook = ZipFile(file).use { zip ->
        val opfPath = findOpfPath(zip) ?: error("EPUB inválido: sem OPF")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseXml(zip, opfPath) ?: error("EPUB inválido: OPF não parseável")

        val manifest = mutableMapOf<String, ZipResourceInfo>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val it = items.item(i)
            val id = it.attributes.getNamedItem("id")?.nodeValue ?: continue
            val href = it.attributes.getNamedItem("href")?.nodeValue ?: continue
            val media = it.attributes.getNamedItem("media-type")?.nodeValue ?: ""
            val fullPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
            manifest[id] = ZipResourceInfo(fullPath, media)
        }

        val chapters = mutableListOf<Chapter>()
        val itemrefs = opf.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until itemrefs.length) {
            val idref = itemrefs.item(i).attributes.getNamedItem("idref")?.nodeValue ?: continue
            val res = manifest[idref] ?: continue
            val entry = zip.getEntry(res.path) ?: continue
            val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            chapters.add(Chapter(res.path, html, res.mediaType))
        }

        val title = opf.firstTag("dc:title", "title") ?: file.nameWithoutExtension
        val author = opf.firstTag("dc:creator", "creator")
        EpubBook(
            metadata = Metadata(title, author),
            chapters = chapters,
            resources = manifest.values.associateBy { it.path }
        )
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = zip.getInputStream(container).use { input ->
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(InputSource(input))
        }
        val roots = doc.getElementsByTagNameNS("*", "rootfile")
        if (roots.length == 0) return null
        return roots.item(0).attributes.getNamedItem("full-path")?.nodeValue
    }

    private fun parseXml(zip: ZipFile, path: String): Document? {
        val entry: ZipEntry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { input ->
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(InputSource(input))
        }
    }

    private fun Document.firstTag(vararg names: String): String? {
        for (name in names) {
            val list = getElementsByTagName(name)
            if (list.length > 0) {
                val t = list.item(0).textContent?.trim()
                if (!t.isNullOrEmpty()) return t
            }
            val local = name.substringAfter(':')
            val listNs = getElementsByTagNameNS("*", local)
            if (listNs.length > 0) {
                val t = listNs.item(0).textContent?.trim()
                if (!t.isNullOrEmpty()) return t
            }
        }
        return null
    }
}
