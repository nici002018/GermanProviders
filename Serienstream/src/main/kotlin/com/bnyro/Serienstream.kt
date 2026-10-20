
package com.bnyro

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element

open class Serienstream : MainAPI() {
    // s.to has been retired; the site's supported alternate domain is the canonical host.
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val hasMainPage = true
    override var lang = "de"

    // "Alle Serien (A–Z)" is the site's complete list: it lets a catalog indexer (MediaServer)
    // discover every series, page by page.
    override val mainPage = mainPageOf(
        "" to "Startseite",
        ALL_SERIES to "Alle Serien (A–Z)",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == ALL_SERIES) {
            val all = allSeries()
            val items = all.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            return newHomePageResponse(
                listOf(HomePageList(request.name, items)),
                hasNext = page * PAGE_SIZE < all.size
            )
        }
        val document = app.get(mainUrl).document

        val homePageLists = document.select(".popular-page > div, .popular-page section, main section").map { elem ->
            val header = elem.selectFirst("div > h2")?.text() ?: return@map null

            val items = elem.select("a.show-card, a[href*=/serie/], a[href*=/serien/]").mapNotNull {
                it.toSearchResult()
            }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }.filterNotNull()

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.get(
            "$mainUrl/suche",
            params = mapOf("term" to query, "tab" to "shows"),
            referer = "$mainUrl/suche",
        ).document

        return resp.select(".results-group .card").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val metaContainer = document.selectFirst(".show-header-wrapper .container-fluid > div")
            ?: document.selectFirst(".show-header-wrapper, .series-header, main")
            ?: throw RuntimeException("Metadata container HTML element not found")

        val title = metaContainer.selectFirst("h1")?.text()
            ?: throw RuntimeException("Failed to find series title")
        val imdbId = document.selectFirst("a[href*='imdb.com/title/']")?.attr("href")
            ?.let { Regex("tt\\d+").find(it)?.value }
        val poster = fixUrlNull(metaContainer.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        })
        val year = metaContainer.selectFirst("h1 + p > a")?.text()?.toIntOrNull()
        val description = metaContainer.select(".description-text").text()
        val actors =
            metaContainer.select("li.series-group:contains(Besetzung:) a").map { it.text() }
        val genres =
            metaContainer.select("li.series-group:contains(Genre:) a").map { it.text() }
        val trailerUrl = metaContainer.selectFirst("button[data-trailer-url]")?.attr("data-trailer-url")

        val seasons = document.select("#season-nav ul > li a, [id*=season] a[href], a[href*=/staffel/]")
            .distinctBy { it.attr("href") }
        val languages = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
        val episodes = seasons.amap { seasonLink ->
            val seasonNumber = Regex("(?:staffel|season)[-/ ]*(\\d+)", RegexOption.IGNORE_CASE)
                .find(seasonLink.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: seasonLink.text().trim().toIntOrNull()
            val seasonDocument = app.get(fixUrl(seasonLink.attr("href"))).document

            seasonDocument.select(".episode-section .episode-row, .episode-row, tr:has(.episode-title-cell)")
                .mapNotNull { eps ->
                val episodeLink = eps.selectFirst("a[href]")?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: Regex("['\"]([^'\"]+)['\"]").find(eps.attr("onclick"))?.groupValues?.get(1)
                    ?: return@mapNotNull null

                // The flags of the row: the language versions this episode is offered in.
                val flags = eps.select(".episode-language-cell svg, .episode-language-cell img").map {
                    it.className() + " " + it.attr("src") + " " + (it.selectFirst("use")?.attr("href") ?: "")
                }
                languages[fixUrl(episodeLink)] = flags.mapNotNull { flagLanguage(it) }.distinct()

                newEpisode(fixUrl(episodeLink)) {
                    this.episode = eps.selectFirst(".episode-number-cell, [itemprop=episodeNumber]")
                        ?.let { it.attr("content").ifBlank { it.text() } }
                        ?.filter { it.isDigit() }?.toIntOrNull()
                    this.name = eps.select(".episode-title-cell > *")
                        .joinToString(" - ") { el -> el.text() }
                        .ifBlank { eps.selectFirst(".episode-title-cell, .episode-title, a[href]")?.text() }
                    this.season = seasonNumber
                }
            }
        }.flatten()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.name = title
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = genres
            addImdbId(imdbId)
            addTrailer(trailerUrl)

            addActors(actors)
            // Episode languages for MediaServer (indexing, "new episode in German" alerts);
            // other apps ignore the unknown key.
            if (languages.values.any { it.isNotEmpty() }) syncData[LANGUAGES_KEY] = languagesJson(languages)
        }
    }

    /** The complete series list of /serien (cached for six hours). */
    private suspend fun allSeries(): List<SearchResponse> {
        val cached = allSeriesCache
        if (cached != null && System.currentTimeMillis() - cached.first < 6 * 3600_000L) return cached.second
        val document = app.get("$mainUrl$ALL_SERIES").document
        val items = document.select("a[href^=/serie/]")
            .filter { Regex("^/serie/[^/?#]+/?$").matches(it.attr("href")) && it.text().isNotBlank() }
            .distinctBy { it.attr("href").trimEnd('/') }
            .map { newTvSeriesSearchResponse(it.text().trim(), fixUrl(it.attr("href")), TvType.TvSeries) }
        allSeriesCache = System.currentTimeMillis() to items
        return items
    }

    companion object {
        const val ALL_SERIES = "/serien"
        const val PAGE_SIZE = 500
        const val LANGUAGES_KEY = "mediaserver.languages"
        @Volatile private var allSeriesCache: Pair<Long, List<SearchResponse>>? = null

        /** A row flag → language version: de / en (audio), de-sub / en-sub (original audio, subtitles). */
        fun flagLanguage(flag: String): String? {
            val f = flag.lowercase()
            return when {
                "japanese-german" in f || "german-sub" in f || "untertitel" in f -> "de-sub"
                "japanese-english" in f || "english-sub" in f -> "en-sub"
                "german" in f || "deutsch" in f -> "de"
                "english" in f || "englisch" in f -> "en"
                else -> null
            }
        }

        /** {"<episode url>": ["de", "en"], …} without a JSON library. */
        fun languagesJson(languages: Map<String, List<String>>): String =
            languages.entries.joinToString(",", "{", "}") { (url, tags) ->
                "\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\":" +
                    tags.joinToString(",", "[", "]") { "\"$it\"" }
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select(".link-wrapper > button, [data-play-url]").distinctBy { it.attr("data-play-url") }.amap {
            val playUrl = it.attr("data-play-url").ifBlank { it.attr("data-href") }
            if (playUrl.isBlank()) return@amap
            val streamUrl = fixUrl(playUrl)
            val source = it.attr("data-provider-name").ifBlank { it.attr("data-host-name") }.ifBlank { it.text().trim() }
            val language = it.attr("data-language-label").ifBlank { it.attr("data-language") }

            val redirectedStreamUrl = app.get(fixUrl(streamUrl)).url
            loadExtractor(redirectedStreamUrl, data, subtitleCallback) { link ->
                val linkWithFixedName = runBlocking {
                    newExtractorLink(
                        source = source,
                        name = if (language.isBlank()) source else "$source [$language]",
                        url = link.url
                    ) {
                        referer = link.referer
                        quality = link.quality
                        type = link.type
                        headers = link.headers
                        extractorData = link.extractorData
                    }
                }
                callback.invoke(linkWithFixedName)
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, h2, h3")?.text()?.takeIf { it.isNotBlank() }
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }
}
