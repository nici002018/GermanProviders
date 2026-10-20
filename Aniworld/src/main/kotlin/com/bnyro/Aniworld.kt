package com.bnyro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesSearchResponse
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class Aniworld : MainAPI() {
    override var mainUrl = "https://aniworld.to"
    override var name = "Aniworld"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val hasMainPage = true
    override var lang = "de"

    // "Alle Animes (A–Z)" is the site's complete list: it lets a catalog indexer (MediaServer)
    // discover every anime, page by page.
    override val mainPage = mainPageOf(
        "" to "Startseite",
        ALL_ANIMES to "Alle Animes (A–Z)",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data == ALL_ANIMES) {
            val all = allAnimes()
            val items = all.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            return newHomePageResponse(
                listOf(HomePageList(request.name, items)),
                hasNext = page * PAGE_SIZE < all.size
            )
        }
        val document = app.get(mainUrl).document

        val homePageLists = document.select("div.carousel").map { ele ->
            val header = ele.selectFirst("h2")?.text() ?: return@map null

            val items = ele.select("div.coverListItem").mapNotNull {
                it.toSearchResult()
            }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }.filterNotNull()

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf(
                "x-requested-with" to "XMLHttpRequest"
            )
        )
        return resp.parsed<SearchResp>().filter {
            !it.link.contains("episode-") && it.link.contains("/stream")
        }.map {
            newTvSeriesSearchResponse(
                it.title?.replace(Regex("</?em>"), "") ?: "",
                fixUrl(it.link),
                TvType.TvSeries
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.series-title span")?.text() ?: return null
        val imdbId = document.selectFirst("a[href*='imdb.com/title/']")?.attr("href")
            ?.let { Regex("tt\\d+").find(it)?.value }
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
        val tags = document.select("div.genres li a").map { it.text() }
        val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
        val description = document.select("p.seri_des").text()
        val actors =
            document.select("li:contains(Schauspieler:) ul li a").map { it.select("span").text() }

        val languages = LinkedHashMap<String, List<String>>()
        val episodes = document.select("div#stream > ul:first-child li").mapNotNull { ele ->
            val seasonLink = ele.selectFirst("a") ?: return@mapNotNull null
            val seasonNumber = seasonLink.text().toIntOrNull()
            val seasonDocument = app.get(fixUrl(seasonLink.attr("href"))).document

            seasonDocument.select("table.seasonEpisodesList tbody tr").map { eps ->
                val episodeUrl = fixUrl(eps.selectFirst("a")?.attr("href") ?: return@map null)
                // The flags of the row: the language versions this episode is offered in.
                languages[episodeUrl] = eps.select("img.flag")
                    .mapNotNull { flagLanguage(it.attr("src") + " " + it.attr("title")) }.distinct()
                newEpisode(episodeUrl) {
                    this.episode = eps.selectFirst("meta[itemprop=episodeNumber]")
                        ?.attr("content")?.toIntOrNull()
                    this.name = eps.selectFirst(".seasonEpisodeTitle")?.text()
                    this.season = seasonNumber
                }
            }.filterNotNull()
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
            this.tags = tags
            addImdbId(imdbId)

            addActors(actors)
            // Episode languages for MediaServer (indexing, "new episode in German" alerts);
            // other apps ignore the unknown key.
            if (languages.values.any { it.isNotEmpty() }) syncData[LANGUAGES_KEY] = languagesJson(languages)
        }
    }

    /** The complete anime list of /animes (cached for six hours). */
    private suspend fun allAnimes(): List<SearchResponse> {
        val cached = allAnimesCache
        if (cached != null && System.currentTimeMillis() - cached.first < 6 * 3600_000L) return cached.second
        val document = app.get("$mainUrl$ALL_ANIMES").document
        val items = document.select("a[href^=/anime/stream/]")
            .filter { Regex("^/anime/stream/[^/?#]+/?$").matches(it.attr("href")) && it.text().isNotBlank() }
            .distinctBy { it.attr("href").trimEnd('/') }
            .map { newTvSeriesSearchResponse(it.text().trim(), fixUrl(it.attr("href")), TvType.TvSeries) }
        allAnimesCache = System.currentTimeMillis() to items
        return items
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("div.hosterSiteVideo ul li").map {
            Triple(
                it.attr("data-lang-key"),
                it.attr("data-link-target"),
                it.select("h4").text()
            )
        }.amap {
            val redirectUrl = app.get(fixUrl(it.second)).url
            val lang = it.first.getLanguage(document)
            val name = "${it.third} [${lang}]"

            loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                val linkWithFixedName = runBlocking {
                    newExtractorLink(
                        source = it.third,
                        name = name,
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

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun String.getLanguage(document: Document): String? {
        return document.selectFirst("div.changeLanguageBox img[data-lang-key=$this]")
            ?.attr("title")?.removePrefix("mit")?.trim()
    }

    companion object {
        const val ALL_ANIMES = "/animes"
        const val PAGE_SIZE = 500
        const val LANGUAGES_KEY = "mediaserver.languages"
        @Volatile private var allAnimesCache: Pair<Long, List<SearchResponse>>? = null

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

        /** {"<episode url>": ["de", "de-sub"], …} without a JSON library. */
        fun languagesJson(languages: Map<String, List<String>>): String =
            languages.entries.joinToString(",", "{", "}") { (url, tags) ->
                "\"" + url.replace("\\", "\\\\").replace("\"", "\\\"") + "\":" +
                    tags.joinToString(",", "[", "]") { "\"$it\"" }
            }
    }

    private class SearchResp: ArrayList<SearchItem>()

    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
