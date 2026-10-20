package com.bnyro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FilmpalastProvider : MainAPI() {
    override var mainUrl = "https://filmpalast.to"
    override var name = "Filmpalast"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var lang = "de"
    override val hasMainPage = true

    // "Alle Filme" and "Alle Serien" are the site's complete lists (newest first): they let a
    // catalog indexer (MediaServer) discover every title, page by page.
    override val mainPage: List<MainPageData> = mainPageOf(
        "" to "Neu",
        "/movies/top" to "Filme",
        "/movies/new" to "Alle Filme",
        "/serien/view" to "Alle Serien"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("a")?.attr("title").orEmpty()
        val url = selectFirst("a")?.attr("href").orEmpty()
        if (title.isBlank() || url.isBlank()) return null
        val posterPath = selectFirst("img[src^='/files']")?.attr("src")

        return newMovieSearchResponse(title, type = TvType.Movie, url = url).apply {
            this.posterUrl = posterPath?.let { "$mainUrl$it" }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl${request.data}/page/${page}").document
        val results = response.select("#content article.liste").mapNotNull {
            it.toSearchResponse()
        }
        // Pages after the last one show the last page again: only a "vorwärts" link means more.
        val hasNext = response.select("a.pageing").any { it.text().contains("vorw") }

        return newHomePageResponse(request, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/title/$query").document
        return document.select("#content .glowliste").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document.select("#content")

        val title = document.select("h2.rb.bgDark").text()
        val imagePath = document.select(".detail.rb img.cover2").attr("src")
        val description = document.select("span[itemprop=description]").text()
        val details = document.select("#detail-content-list li")
        val year = details.text().substringAfter("Veröffentlicht:")
            .takeWhile { it.isDigit() || it.isWhitespace() }.trim().toIntOrNull()
        val duration = details.text().substringAfter("Spielzeit:")
            .takeWhile { it.isDigit() || it.isWhitespace() }.trim().toIntOrNull()
        val genres = details.select("a[href*=genre]").map { it.text() }
        val actors = details.select("a[href*=title]").map { it.text() }
        val score = document.select(".detail").text().substringAfter("Imdb: ").substringBefore("/").toFloatOrNull()
            ?.let { Score.from10(it) }

        val links = document.select(".currentStreamLinks a.iconPlay").mapNotNull {
            it.attr("href").ifEmpty { it.attr("data-player-url") }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, LoadData(links).toJson()).apply {
            this.posterUrl = fixUrl(imagePath)
            this.plot = description
            this.duration = duration
            this.year = year
            this.tags = genres
            this.score = score

            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<LoadData>(data).links

        links.amap {
            val link = fixUrlNull(it) ?: return@amap null
            loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
        }
        return links.isNotEmpty()
    }

    data class LoadData(
        val links: List<String>
    )
}
