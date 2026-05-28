package com.mae.reader.epub

import android.content.Context
import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubParser(private val context: Context) {

    fun parse(uri: Uri): EpubBook {
        val entries = readZipEntries(uri)

        val containerXml = entries["META-INF/container.xml"]
            ?: error("EPUB inválido: falta container.xml")
        val opfPath = extractOpfPath(containerXml)
        val opfDir = opfPath.substringBeforeLast("/", "")

        val opfXml = entries[opfPath] ?: error("EPUB inválido: falta OPF en $opfPath")
        val (title, author, spineIds, manifest, coverId) = parseOpf(opfXml)

        // Construir capítulos en orden del spine
        val chapters = mutableListOf<Chapter>()
        for (id in spineIds) {
            val href = manifest[id] ?: continue
            val fullPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val htmlBytes = entries[fullPath] ?: entries[href] ?: continue
            val cleaned = cleanHtml(htmlBytes, fullPath)
            chapters.add(Chapter(id = id, title = "", htmlContent = cleaned))
        }

        // TOC desde NCX o nav
        val ncxPath = manifest.values.firstOrNull { it.endsWith(".ncx") }
            ?.let { if (opfDir.isEmpty()) it else "$opfDir/$it" }
        val navPath = manifest.values.firstOrNull { it.contains("nav") && it.endsWith(".xhtml") }
            ?.let { if (opfDir.isEmpty()) it else "$opfDir/$it" }

        val toc = when {
            ncxPath != null && entries.containsKey(ncxPath) ->
                parseTocNcx(entries[ncxPath]!!, spineIds)
            navPath != null && entries.containsKey(navPath) ->
                parseTocNav(entries[navPath]!!, spineIds)
            else -> buildFallbackToc(chapters.size)
        }

        // Asignar títulos de TOC a capítulos
        val titled = chapters.mapIndexed { idx, ch ->
            val tocTitle = toc.firstOrNull { it.chapterIndex == idx }?.title
            ch.copy(title = tocTitle ?: "Capítulo ${idx + 1}")
        }

        // Extraer bytes de la portada usando el id detectado
        val coverBytes: ByteArray? = coverId?.let { id ->
            manifest[id]?.let { href ->
                val fullPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
                entries[fullPath] ?: entries[href]
            }
        }

        return EpubBook(title = title, author = author, chapters = titled, toc = toc, coverBytes = coverBytes)
    }

    private fun readZipEntries(uri: Uri): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return map
    }

    private fun extractOpfPath(containerBytes: ByteArray): String {
        val doc = parseXml(containerBytes.inputStream())
        val rootfiles = doc.getElementsByTagName("rootfile")
        for (i in 0 until rootfiles.length) {
            val el = rootfiles.item(i) as? Element ?: continue
            return el.getAttribute("full-path")
        }
        error("No se encontró OPF path en container.xml")
    }

    data class OpfData(
        val title: String,
        val author: String,
        val spineIds: List<String>,
        val manifest: Map<String, String>,   // id -> href
        val coverId: String?
    )

    private fun parseOpf(opfBytes: ByteArray): OpfData {
        val doc = parseXml(opfBytes.inputStream())

        val title = doc.getElementsByTagName("dc:title").item(0)?.textContent?.trim() ?: "Sin título"
        val author = doc.getElementsByTagName("dc:creator").item(0)?.textContent?.trim() ?: ""

        // Manifest: id -> href; detectar portada EPUB 3 (properties="cover-image")
        val manifest = mutableMapOf<String, String>()
        var coverId: String? = null
        doc.getElementsByTagName("item").forEachElement { el ->
            val id   = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = href
                if (el.getAttribute("properties").contains("cover-image")) coverId = id
            }
        }

        // EPUB 2: <meta name="cover" content="cover-id"/>
        if (coverId == null) {
            doc.getElementsByTagName("meta").forEachElement { el ->
                if (el.getAttribute("name") == "cover") {
                    val id = el.getAttribute("content")
                    if (manifest.containsKey(id)) coverId = id
                }
            }
        }

        // Fallback: item cuyo id o href contenga "cover" y sea imagen
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif")
        if (coverId == null) {
            coverId = manifest.entries.firstOrNull { (id, href) ->
                (id.contains("cover", ignoreCase = true) ||
                 href.contains("cover", ignoreCase = true)) &&
                href.substringAfterLast(".").lowercase() in imageExts
            }?.key
        }

        // Spine: orden de lectura
        val spineIds = mutableListOf<String>()
        doc.getElementsByTagName("itemref").forEachElement { el ->
            val idref = el.getAttribute("idref")
            if (idref.isNotEmpty()) spineIds.add(idref)
        }

        return OpfData(title, author, spineIds, manifest, coverId)
    }

    private fun cleanHtml(bytes: ByteArray, path: String): String {
        val raw = bytes.toString(Charsets.UTF_8)
        val doc: Document = Jsoup.parse(raw)
        doc.select("script, style[type='text/css']").remove()
        // Preservar imágenes relativas ajustando src — simplificado para MVP
        return doc.body().html()
    }

    private fun parseTocNcx(bytes: ByteArray, spineIds: List<String>): List<TocEntry> {
        val doc = parseXml(bytes.inputStream())
        val navPoints = doc.getElementsByTagName("navPoint")
        val result = mutableListOf<TocEntry>()
        navPoints.forEachElement { el ->
            val label = el.getElementsByTagName("text").item(0)?.textContent?.trim() ?: return@forEachElement
            val src = (el.getElementsByTagName("content").item(0) as? Element)
                ?.getAttribute("src")?.substringAfter("/")?.substringBefore("#") ?: return@forEachElement
            val idx = spineIds.indexOfFirst { id -> id.contains(src.substringBeforeLast(".")) }
            if (idx >= 0) result.add(TocEntry(label, idx))
        }
        return result.distinctBy { it.chapterIndex }
    }

    private fun parseTocNav(bytes: ByteArray, spineIds: List<String>): List<TocEntry> {
        val doc = Jsoup.parse(bytes.toString(Charsets.UTF_8))
        val result = mutableListOf<TocEntry>()
        doc.select("nav[epub|type=toc] a, nav li a").forEach { a ->
            val label = a.text().trim()
            val href = a.attr("href").substringBefore("#").substringAfterLast("/")
            val idx = spineIds.indexOfFirst { it.contains(href.substringBeforeLast(".")) }
            if (label.isNotEmpty() && idx >= 0) result.add(TocEntry(label, idx))
        }
        return result.distinctBy { it.chapterIndex }
    }

    private fun buildFallbackToc(count: Int): List<TocEntry> =
        (0 until count).map { TocEntry("Capítulo ${it + 1}", it) }

    private fun parseXml(stream: InputStream) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(stream)

    private fun NodeList.forEachElement(block: (Element) -> Unit) {
        for (i in 0 until length) (item(i) as? Element)?.let(block)
    }
}
