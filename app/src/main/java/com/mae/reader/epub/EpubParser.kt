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
        val (title, author, spineIds, manifest, coverId, navId, language) = parseOpf(opfXml)

        // Construir capítulos en orden del spine. Se registra a qué índice de
        // `chapters` termina cada posición del spine, porque un idref sin
        // contenido válido se salta y desalinearía los índices si se asumiera
        // spineIndex == chapterIndex.
        val chapters = mutableListOf<Chapter>()
        val spineIndexToChapterIndex = mutableMapOf<Int, Int>()
        spineIds.forEachIndexed { spineIdx, id ->
            val href = manifest[id] ?: return@forEachIndexed
            val fullPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val htmlBytes = entries[fullPath] ?: entries[href] ?: return@forEachIndexed
            val cleaned = cleanHtml(htmlBytes, fullPath)
            spineIndexToChapterIndex[spineIdx] = chapters.size
            chapters.add(Chapter(id = id, title = "", htmlContent = cleaned))
        }

        // Mapa href (normalizado, relativo al OPF) -> índice en el spine.
        // Es la clave real para ubicar un TocEntry: el spine usa idref (id del
        // manifest), mientras que NCX/nav referencian archivos por href, y en
        // muchos EPUB el id no guarda ninguna relación textual con su href.
        val hrefToSpineIndex = mutableMapOf<String, Int>()
        spineIds.forEachIndexed { spineIdx, id ->
            manifest[id]?.let { href -> hrefToSpineIndex[resolveHref(opfDir, href)] = spineIdx }
        }

        // TOC desde NCX o nav. El nav de EPUB3 se identifica por
        // properties="nav" en el manifest (norma del formato); el nombre de
        // archivo es libre, así que basarse en "contiene 'nav'" fallaba para
        // navs con otro nombre (p.ej. toc.xhtml).
        val ncxPath = manifest.values.firstOrNull { it.endsWith(".ncx") }
            ?.let { if (opfDir.isEmpty()) it else "$opfDir/$it" }
        val navPath = (navId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.contains("nav") && it.endsWith(".xhtml") })
            ?.let { if (opfDir.isEmpty()) it else "$opfDir/$it" }

        val toc = when {
            ncxPath != null && entries.containsKey(ncxPath) ->
                parseTocNcx(entries[ncxPath]!!, ncxPath.substringBeforeLast("/", ""), hrefToSpineIndex, spineIndexToChapterIndex)
            navPath != null && entries.containsKey(navPath) ->
                parseTocNav(entries[navPath]!!, navPath.substringBeforeLast("/", ""), hrefToSpineIndex, spineIndexToChapterIndex)
            else -> buildFallbackToc(chapters.size)
        }.ifEmpty { buildFallbackToc(chapters.size) }

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

        return EpubBook(title = title, author = author, chapters = titled, toc = toc, coverBytes = coverBytes, language = language)
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
        val coverId: String?,
        val navId: String?,
        val language: String
    )

    private fun parseOpf(opfBytes: ByteArray): OpfData {
        val doc = parseXml(opfBytes.inputStream())

        val title = doc.getElementsByTagName("dc:title").item(0)?.textContent?.trim() ?: "Sin título"
        val author = doc.getElementsByTagName("dc:creator").item(0)?.textContent?.trim() ?: ""
        val language = doc.getElementsByTagName("dc:language").item(0)?.textContent?.trim() ?: ""

        // Manifest: id -> href; detectar portada (properties="cover-image") y
        // nav de EPUB 3 (properties="nav")
        val manifest = mutableMapOf<String, String>()
        var coverId: String? = null
        var navId: String? = null
        doc.getElementsByTagName("item").forEachElement { el ->
            val id   = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = href
                val properties = el.getAttribute("properties")
                if (properties.contains("cover-image")) coverId = id
                if (properties.contains("nav")) navId = id
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

        return OpfData(title, author, spineIds, manifest, coverId, navId, language)
    }

    private fun cleanHtml(bytes: ByteArray, path: String): String {
        val raw = bytes.toString(Charsets.UTF_8)
        val doc: Document = Jsoup.parse(raw)
        doc.select("script, style[type='text/css']").remove()
        // Preservar imágenes relativas ajustando src — simplificado para MVP
        return doc.body().html()
    }

    private fun parseTocNcx(
        bytes: ByteArray,
        ncxDir: String,
        hrefToSpineIndex: Map<String, Int>,
        spineIndexToChapterIndex: Map<Int, Int>
    ): List<TocEntry> {
        val doc = parseXml(bytes.inputStream())
        val navPoints = doc.getElementsByTagName("navPoint")
        val result = mutableListOf<TocEntry>()
        navPoints.forEachElement { el ->
            val label = el.getElementsByTagName("text").item(0)?.textContent?.trim() ?: return@forEachElement
            val src = (el.getElementsByTagName("content").item(0) as? Element)
                ?.getAttribute("src") ?: return@forEachElement
            val spineIdx = resolveTocTarget(ncxDir, src.substringBefore("#"), hrefToSpineIndex) ?: return@forEachElement
            val chapterIdx = spineIndexToChapterIndex[spineIdx] ?: return@forEachElement
            if (label.isNotEmpty()) result.add(TocEntry(label, chapterIdx, anchorOf(src)))
        }
        // Varias entradas pueden apuntar al mismo archivo (secciones o libros de
        // un solo HTML); se conservan todas y el lector salta a cada sección
        return result.distinct()
    }

    private fun parseTocNav(
        bytes: ByteArray,
        navDir: String,
        hrefToSpineIndex: Map<String, Int>,
        spineIndexToChapterIndex: Map<Int, Int>
    ): List<TocEntry> {
        val doc = Jsoup.parse(bytes.toString(Charsets.UTF_8))
        val tocNav = doc.select("nav[epub|type=toc]").firstOrNull() ?: doc.select("nav").firstOrNull()
        val result = mutableListOf<TocEntry>()
        (tocNav?.select("a") ?: emptyList()).forEach { a ->
            val label = a.text().trim()
            val rawHref = a.attr("href")
            val href = rawHref.substringBefore("#")
            if (label.isEmpty() || href.isEmpty()) return@forEach
            val spineIdx = resolveTocTarget(navDir, href, hrefToSpineIndex) ?: return@forEach
            val chapterIdx = spineIndexToChapterIndex[spineIdx] ?: return@forEach
            result.add(TocEntry(label, chapterIdx, anchorOf(rawHref)))
        }
        return result.distinct()
    }

    private fun anchorOf(href: String): String? =
        href.substringAfter("#", "").ifEmpty { null }

    // Resuelve el target de una entrada de TOC (relativo al NCX/nav) contra el
    // mapa de hrefs del spine (relativo al OPF). Si la resolución exacta falla
    // — rutas mal formadas, "../" fuera de rango — hace un último intento
    // comparando solo el nombre de archivo, más confiable que adivinar por id.
    private fun resolveTocTarget(baseDir: String, target: String, hrefToSpineIndex: Map<String, Int>): Int? {
        val resolved = resolveHref(baseDir, target)
        hrefToSpineIndex[resolved]?.let { return it }
        val fileName = resolved.substringAfterLast("/")
        return hrefToSpineIndex.entries.firstOrNull { it.key.substringAfterLast("/") == fileName }?.value
    }

    // Resuelve un href relativo (con posibles "./" y "../") contra un
    // directorio base, ambos en notación de EPUB (separador "/").
    private fun resolveHref(baseDir: String, href: String): String {
        if (href.startsWith("/")) return href.trimStart('/')
        val parts = if (baseDir.isEmpty()) mutableListOf() else baseDir.split("/").toMutableList()
        for (segment in href.split("/")) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
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
