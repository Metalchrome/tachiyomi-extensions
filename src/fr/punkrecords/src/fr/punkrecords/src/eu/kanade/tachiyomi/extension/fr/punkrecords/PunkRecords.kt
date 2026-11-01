package eu.kanade.tachiyomi.extension.fr.punkrecords

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PunkRecords : HttpSource() {

    override val name = "Punk Records"

    override val baseUrl = "https://punkrecordz.com"

    override val lang = "fr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/catalogue", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseCatalogueFromDom(doc).ifEmpty {
            parseCatalogueFromRsc(extractNextRscPayload(doc))
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/catalogue?sort=updated", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/catalogue".toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addQueryParameter("q", query.trim())
        }
        filters.forEach { filter ->
            if (filter is SortFilter) {
                val sortValue = filter.toUriPart()
                if (sortValue.isNotEmpty()) {
                    url.addQueryParameter("sort", sortValue)
                }
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page = popularMangaParse(response)
        val requestUrl = response.request.url
        val query = requestUrl.queryParameter("q")?.trim().orEmpty()
        val sort = requestUrl.queryParameter("sort").orEmpty()

        var filtered = page.mangas
        if (query.isNotEmpty()) {
            filtered = filtered.filter { manga ->
                manga.title.contains(query, ignoreCase = true)
            }
        }
        if (sort == "alpha") {
            filtered = filtered.sortedBy { it.title.lowercase(Locale.FRENCH) }
        }
        return MangasPage(filtered, hasNextPage = false)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
    )

    private class SortFilter : Filter.Select<String>(
        "Trier par",
        arrayOf("Populaires", "Récents", "A-Z"),
    ) {
        private val vals = arrayOf("", "updated", "alpha")
        fun toUriPart(): String = vals[state]
    }

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val rsc = extractNextRscPayload(doc)
        val slug = response.request.url.pathSegments.lastOrNull().orEmpty()

        return SManga.create().apply {
            // 1. Extraction depuis le DOM
            title = doc.selectFirst("h1")?.text()?.trim()
                ?: extractJsonString(rsc, "title")
                ?: slug

            // Ligne de métadonnées DOM : "EIICHIRO ODA · 1997 · 1179 CHAPITRES · SHŌNEN"
            val metaLine = doc.select("p, div, span")
                .map { it.ownText().trim() }
                .firstOrNull { it.contains("·") && it.uppercase().contains("CHAPITRE") }

            if (metaLine != null) {
                val parts = metaLine.split("·").map { it.trim() }
                author = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                genre = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            }

            // 2. Enrichissement / fallback via le payload Next.js RSC
            if (author.isNullOrBlank()) {
                author = extractJsonString(rsc, "author")
            }
            if (genre.isNullOrBlank()) {
                genre = extractJsonString(rsc, "type")
            }

            description = extractJsonString(rsc, "description")
                ?: doc.select("main p")
                    .map { it.text().trim() }
                    .firstOrNull { it.length > 40 && !it.contains("·") }

            thumbnail_url = extractMangaCoverFromRsc(rsc)
                ?: doc.selectFirst("main img[src*=/cover], main img")?.attr("abs:src")

            status = SManga.UNKNOWN
            initialized = true
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val rsc = extractNextRscPayload(doc)
        val mangaSlug = response.request.url.pathSegments.lastOrNull().orEmpty()

        val chaptersFromRsc = parseChaptersFromRsc(rsc, mangaSlug)
        if (chaptersFromRsc.isNotEmpty()) {
            return chaptersFromRsc.sortedByDescending { it.chapter_number }
        }

        // Fallback DOM si les chapitres sont rendus sous forme de liens <a href="/one-piece/1197">
        return doc.select("a[href^=/$mangaSlug/]")
            .mapNotNull { element ->
                val href = element.attr("href").trim()
                val chapterSlug = href.removePrefix("/$mangaSlug/").trim('/')
                if (chapterSlug.isEmpty() || chapterSlug.contains('/')) return@mapNotNull null

                val numFloat = chapterSlug.toFloatOrNull() ?: -1f
                SChapter.create().apply {
                    url = href
                    chapter_number = numFloat
                    name = "Chapitre $chapterSlug"
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val rsc = extractNextRscPayload(doc)

        // 1. Extraction depuis le tableau "pages":[{"key":"...","url":"https://..."}] du payload Next.js RSC
        val rscPages = PAGE_URL_REGEX.findAll(rsc)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        if (rscPages.isNotEmpty()) {
            return rscPages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
        }

        // 2. Fallback DOM sur les balises <img> des planches
        return doc.select("img[src*=/pages/], main img[src]")
            .map { it.attr("abs:src") }
            .filter { it.isNotBlank() && !it.contains("/cover") && !it.contains("logo") }
            .distinct()
            .mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Non utilisé.")

    // ============================= Helpers ================================

    private fun parseCatalogueFromDom(doc: Document): List<SManga> {
        return doc.select("a[href^=/manga/]")
            .mapNotNull { card ->
                val href = card.attr("href").trim()
                if (href.isEmpty() || href == "/manga" || href == "/manga/") return@mapNotNull null

                val titleText = card.selectFirst("h3, h2, h4")?.text()?.trim()
                    ?: card.selectFirst("img")?.attr("alt")?.trim()
                    ?: return@mapNotNull null

                val coverUrl = card.selectFirst("img")?.attr("abs:src")

                SManga.create().apply {
                    url = href
                    title = titleText
                    thumbnail_url = coverUrl
                }
            }
            .distinctBy { it.url }
    }

    private fun parseCatalogueFromRsc(rsc: String): List<SManga> {
        return MANGA_OBJECT_REGEX.findAll(rsc)
            .map { match ->
                val slug = match.groupValues[1]
                val titleText = unescapeJsonText(match.groupValues[2])
                val coverUrl = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                SManga.create().apply {
                    url = "/manga/$slug"
                    title = titleText
                    thumbnail_url = coverUrl?.let { resolveRelativeUrl(it) }
                }
            }
            .distinctBy { it.url }
            .toList()
    }

    private fun parseChaptersFromRsc(rsc: String, mangaSlug: String): List<SChapter> {
        return CHAPTER_OBJECT_REGEX.findAll(rsc)
            .map { match ->
                val chapSlug = match.groupValues[1]
                val chapNumStr = match.groupValues[2]
                val chapTitle = unescapeJsonText(match.groupValues[3]).trim()
                val publishedAt = match.groupValues[4]

                val numFloat = chapNumStr.toFloatOrNull()
                    ?: chapSlug.toFloatOrNull()
                    ?: -1f

                val displayNum = chapSlug.ifEmpty { chapNumStr }
                val formattedName = if (
                    chapTitle.isNotEmpty() &&
                    !chapTitle.equals("Chapitre $displayNum", ignoreCase = true) &&
                    !chapTitle.equals(displayNum, ignoreCase = true)
                ) {
                    "Chapitre $displayNum - $chapTitle"
                } else {
                    "Chapitre $displayNum"
                }

                SChapter.create().apply {
                    url = "/$mangaSlug/$displayNum"
                    name = formattedName
                    chapter_number = numFloat
                    date_upload = parseIsoDate(publishedAt)
                }
            }
            .distinctBy { it.url }
            .toList()
    }

    /**
     * Rassemble et déséchappe les chunks `self.__next_f.push([1, "..."])` de Next.js App Router.
     */
    private fun extractNextRscPayload(doc: Document): String {
        val rawScripts = doc.select("script")
            .map { it.data() }
            .filter { it.contains("__next_f.push") || it.contains("__NEXT_DATA__") }
            .joinToString("\n")

        return rawScripts
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
    }

    private fun extractJsonString(rsc: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]+)"""")
        return regex.find(rsc)?.groupValues?.getOrNull(1)?.let { unescapeJsonText(it) }
    }

    private fun extractMangaCoverFromRsc(rsc: String): String? {
        val cover = extractJsonString(rsc, "cover") ?: return null
        return resolveRelativeUrl(cover)
    }

    private fun resolveRelativeUrl(urlOrPath: String): String {
        return if (urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://")) {
            urlOrPath
        } else {
            "$baseUrl/${urlOrPath.trimStart('/')}"
        }
    }

    private fun unescapeJsonText(input: String): String {
        return input
            .replace("\\u0026", "&")
            .replace("\\u0027", "'")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
    }

    private fun parseIsoDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching { ISO_DATE_FORMAT_MS.parse(dateStr)?.time }.getOrNull()
            ?: runCatching { ISO_DATE_FORMAT_SEC.parse(dateStr)?.time }.getOrNull()
            ?: 0L
    }

    companion object {
        // Extrait les URLs de planches dans "pages":[{"key":"...","url":"https://.../pages/..."}]
        private val PAGE_URL_REGEX = Regex(
            """"url"\s*:\s*"(https?://[^"]+/chapters/[^"]+/pages/[^"]+)""""
        )

        // Extrait les chapitres depuis le JSON RSC : {"id":"...","slug":"1197","number":1197,"title":"...","publishedAt":"..."}
        private val CHAPTER_OBJECT_REGEX = Regex(
            """"slug"\s*:\s*"([^"]+)"\s*,\s*"number"\s*:\s*([0-9.]+)\s*,\s*"title"\s*:\s*(?:"([^"]*)"|null)(?:[^}]*?"publishedAt"\s*:\s*"([^"]+)")?"""
        )

        // Fallback catalogue depuis le JSON RSC : {"id":"...","slug":"one-piece","title":"ONE PIECE",...,"cover":"..."}
        private val MANGA_OBJECT_REGEX = Regex(
            """"slug"\s*:\s*"([a-z0-9-]+)"\s*,\s*"title"\s*:\s*"([^"]+)"[^}]*?"cover"\s*:\s*"([^"]+)""""
        )

        private val ISO_DATE_FORMAT_MS by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        private val ISO_DATE_FORMAT_SEC by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
