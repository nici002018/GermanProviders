package com.bnyro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

/**
 * FilmPalast lists every episode of a series as its own entry ("Vikings S01E09"). Lists show
 * one entry per series under its own name ("Vikings"); loading any episode page returns the
 * series with all seasons and episodes from the page's season list. Years in names
 * ("Blindgänger *2024*") become the year.
 */
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
        val raw = selectFirst("a")?.attr("title").orEmpty()
        val url = selectFirst("a")?.attr("href").orEmpty()
        if (raw.isBlank() || url.isBlank()) return null
        val posterPath = selectFirst("img[src^='/files']")?.attr("src")
        val episode = EPISODE.find(raw)
        val (name, year) = cleanName(if (episode != null) raw.substring(0, episode.range.first) else raw)

        return newMovieSearchResponse(name, type = if (episode != null) TvType.TvSeries else TvType.Movie, url = url).apply {
            this.posterUrl = posterPath?.let { "$mainUrl$it" }
            this.year = year
        }
    }

    /** One entry per series (the newest listed episode stands for it). */
    private fun List<SearchResponse>.oneEntryPerSeries() = distinctBy { if (it.type == TvType.TvSeries) "s:" + it.name.lowercase() else it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl${request.data}/page/${page}").document
        val results = response.select("#content article.liste").mapNotNull {
            it.toSearchResponse()
        }.oneEntryPerSeries()
        // Pages after the last one show the last page again: only a "vorwärts" link means more.
        val hasNext = response.select("a.pageing").any { it.text().contains("vorw") }

        return newHomePageResponse(request, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/title/$query").document
        return document.select("#content .glowliste").mapNotNull {
            it.toSearchResponse()
        }.oneEntryPerSeries()
    }

    override suspend fun load(url: String): LoadResponse {
        val page = app.get(url).document
        val document = page.select("#content")

        val rawTitle = document.select("h2.rb.bgDark").text()
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

        val links = streamLinks(page)
        val poster = fixUrl(imagePath)

        // A series: every episode of every season from the page's season list.
        val episodeLinks = page.select(".staffelWrapperLoop").flatMap { loop ->
            val season = loop.attr("data-sid").toIntOrNull()
            loop.select("a.getStaffelStream").mapNotNull { a ->
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val code = EPISODE.find(a.text()) ?: EPISODE.find(href.replace('-', ' '))
                val s = code?.groupValues?.get(1)?.toIntOrNull() ?: season ?: return@mapNotNull null
                val e = code?.groupValues?.get(2)?.toIntOrNull() ?: return@mapNotNull null
                Triple(s, e, if (href.startsWith("//")) "https:$href" else fixUrl(href))
            }
        }.distinctBy { it.first to it.second }
        val episodeCode = EPISODE.find(rawTitle)
        if (episodeLinks.isNotEmpty() || episodeCode != null) {
            val (seriesName, _) = cleanName(if (episodeCode != null) rawTitle.substring(0, episodeCode.range.first) else rawTitle)
            val episodes = episodeLinks.sortedWith(compareBy({ it.first }, { it.second })).map { (s, e, href) ->
                newEpisode(EpisodeData(href).toJson()) {
                    this.season = s
                    this.episode = e
                    this.name = "Episode $e"
                }
            }
            return newTvSeriesLoadResponse(seriesName, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
                this.score = score
                addActors(actors)
            }
        }
        val (title, nameYear) = cleanName(rawTitle)
        return newMovieLoadResponse(title, url, TvType.Movie, LoadData(links).toJson()).apply {
            this.posterUrl = poster
            this.plot = description
            this.duration = duration
            this.year = year ?: nameYear
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
        // An episode: its page's stream links; a movie: the links found on load.
        val links = if (data.contains("\"episode\"")) {
            streamLinks(app.get(parseJson<EpisodeData>(data).episode).document)
        } else parseJson<LoadData>(data).links

        links.amap {
            val link = fixUrlNull(it) ?: return@amap null
            loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
        }
        return links.isNotEmpty()
    }

    private fun streamLinks(page: org.jsoup.nodes.Document) = page.select(".currentStreamLinks a.iconPlay").mapNotNull {
        it.attr("href").ifEmpty { it.attr("data-player-url") }.ifBlank { null }
    }

    data class LoadData(
        val links: List<String>
    )

    data class EpisodeData(
        val episode: String
    )

    companion object {
        /** "Vikings S01E09" → season 1, episode 9. */
        val EPISODE = Regex("""\s*\bS(\d{1,2})\s*E(\d{1,3})\b.*$""", RegexOption.IGNORE_CASE)
        private val STAR_YEAR = Regex("""\s*\*\s*((?:19|20)\d\d)\s*\*\s*""")

        /** "Blindgänger *2024*" → ("Blindgänger", 2024); other names stay. */
        fun cleanName(raw: String): Pair<String, Int?> {
            val m = STAR_YEAR.find(raw)
            val name = (if (m != null) raw.removeRange(m.range) else raw).trim().trimEnd('-', '–', ':').trim()
            return (name.ifEmpty { raw.trim() }) to m?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
