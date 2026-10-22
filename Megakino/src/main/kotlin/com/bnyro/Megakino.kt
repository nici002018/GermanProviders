package com.bnyro

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Megakino (megakino24.com and the domains redirecting to it).
 *
 * The site sends every visitor without a fresh `yg_token` cookie a small page that fetches
 * `/index.php?yg=token` and reloads; the cookie expires after 15 minutes. The token is
 * renewed before it expires and whenever that page comes back. The site also moves domains:
 * the redirect of [mainUrl] and known mirrors are tried in turn. Title URLs keep [mainUrl] as
 * their host (stable identities for apps that store them); requests and images use the
 * domain that currently answers.
 *
 * Series have one page per season ("Brothers - 1 Staffel"): loading a season finds the other
 * seasons of the same series through the site search and returns one series with all of them.
 */
class Megakino : MainAPI() {
    override var mainUrl = "https://megakino4.to"
    override var name = "Megakino"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)

    // "Alle Filme" and "Alle Serien" are the site's complete lists (newest first): they let a
    // catalog indexer (MediaServer) discover every title, page by page.
    override val mainPage = mainPageOf(
        "" to "Trends",
        "kinofilme" to "Kinofilme",
        "films" to "Alle Filme",
        "serials" to "Alle Serien",
        "documentary" to "Dokumentationen",
    )

    private data class Session(val base: String, val cookies: Map<String, String>, val createdAt: Long)

    @Volatile
    private var session: Session? = null

    /** The domain that answers first, then the redirects of older domains (all end up there). */
    private val mirrors = listOf("https://megakino24.com", "https://megakino5.org", "https://megakino1.cx", "https://megakino4.to", "https://megakino1.to")

    /** One token renewal at a time: parallel requests (season pages, the indexer) share it. */
    private val renewing = Mutex()

    /**
     * The current session; [renew]: a new token unless another request renewed it after
     * [seen] (the session that request used) already.
     */
    private suspend fun session(renew: Boolean = false, moveDomain: Boolean = false, seen: Session? = null): Session {
        val current = session
        if (current != null && !renew && !moveDomain && System.currentTimeMillis() - current.createdAt < TOKEN_LIFETIME_MS) return current
        return renewing.withLock {
            val now = session
            // Renewed meanwhile by another request: use that one.
            if (now != null && seen != null && now !== seen && !moveDomain) return@withLock now
            if (now != null && !renew && !moveDomain && System.currentTimeMillis() - now.createdAt < TOKEN_LIFETIME_MS) return@withLock now
            val base = if (now != null && !moveDomain) now.base else findBase()
            val cookies = token(base)
            // Give the site a moment to accept the new token.
            delay(400)
            Session(base, cookies, System.currentTimeMillis()).also { session = it }
        }
    }

    /** The yg_token cookie (read from the response headers too, in case a client drops it). */
    private suspend fun token(base: String): Map<String, String> {
        return try {
            val response = app.get("$base/index.php?yg=token", headers = HEADERS, referer = "$base/")
            val cookies = response.cookies.toMutableMap()
            if (!cookies.containsKey("yg_token")) {
                response.headers.values("set-cookie").firstNotNullOfOrNull { Regex("yg_token=([^;]+)").find(it)?.groupValues?.get(1) }
                    ?.let { cookies["yg_token"] = it }
            }
            cookies
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    private suspend fun findBase(): String {
        for (mirror in mirrors) {
            try {
                val response = app.get(mirror, headers = HEADERS, timeout = 20)
                if (response.code in 200..299) return baseOf(response.url)
            } catch (e: Throwable) {
                // Blocked or gone: the next mirror.
            }
        }
        return mirrors[1]
    }

    /** The token page instead of content: the cookie is missing or expired. */
    private fun gated(html: String) = html.contains("index.php?yg=token") && !html.contains("dle-content")

    /** GET of a site path with the token, renewed (and the domain re-checked) when needed. */
    private fun headers(s: Session) = HEADERS + (s.cookies["yg_token"]?.let { mapOf("Cookie" to "yg_token=$it") } ?: emptyMap())

    private suspend fun page(path: String): Document {
        var s = session()
        for (attempt in 0 until ATTEMPTS) {
            val html = try {
                app.get(s.base + path, headers = headers(s), cookies = s.cookies, referer = "${s.base}/").text
            } catch (e: Throwable) {
                if (attempt == ATTEMPTS - 1) throw e
                s = session(moveDomain = true, seen = s)
                continue
            }
            if (!gated(html)) return Jsoup.parse(html, s.base)
            // A new token (the domain checked again on the third try), a little later each time.
            delay(500L * attempt)
            s = session(renew = true, moveDomain = attempt == 2, seen = s)
        }
        throw IllegalStateException("Megakino is busy (protection page); try again in a minute")
    }

    private suspend fun searchPage(query: String): Document {
        var s = session()
        for (attempt in 0 until ATTEMPTS) {
            val html = app.post(
                s.base + "/",
                headers = headers(s),
                cookies = s.cookies,
                referer = "${s.base}/",
                data = mapOf("do" to "search", "subaction" to "search", "story" to query),
            ).text
            if (!gated(html)) return Jsoup.parse(html, s.base)
            delay(500L * attempt)
            s = session(renew = true, moveDomain = attempt == 2, seen = s)
        }
        throw IllegalStateException("Megakino is busy (protection page); try again in a minute")
    }

    /** A path ("/films/1-x.html") under [mainUrl]: the stable URL of a title. */
    private fun titleUrl(href: String): String {
        val path = try { URI(href).rawPath } catch (e: Exception) { href }
        return mainUrl + (if (path.startsWith("/")) path else "/$path")
    }

    /** An image path on the domain that currently answers. */
    private fun image(base: String, src: String?): String? {
        if (src.isNullOrBlank() || src.contains("no-img")) return null
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> base + (if (src.startsWith("/")) src else "/$src")
        }
    }

    private fun Element.toSearchResult(base: String): SearchResponse? {
        val title = selectFirst("h3")?.text()?.trim().orEmpty()
        val href = attr("href")
        if (title.isEmpty() || href.isBlank()) return null
        // "United Kingdom, 1962" and "Filme / Adventure / Thriller" (or "Serien / …").
        val info = select(".poster__subtitle li").map { it.text() }
        val year = info.firstOrNull()?.let { yearOf(it) }
        val series = info.getOrNull(1)?.startsWith("Serien") == true || seasonOf(title) != null
        val label = selectFirst(".poster__label")?.text()
        // "The Boys - 5 Staffel": one entry per series, the seasons come together on load.
        return newMovieSearchResponse(withoutSeason(title), titleUrl(href), if (series) TvType.TvSeries else TvType.Movie, fix = false) {
            this.posterUrl = image(base, selectFirst("img")?.attr("data-src"))
            this.year = year
            this.quality = if (label.equals("CAM", true)) SearchQuality.Cam else SearchQuality.HD
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (request.data.isEmpty()) "/page/$page/" else "/${request.data}/page/$page/"
        val document = page(path)
        val base = session().base
        val home = document.select("#dle-content > a").mapNotNull { it.toSearchResult(base) }.distinctBy { it.name.lowercase() to it.type }
        // Only the "load more" link of the pagination means there is another page.
        val hasNext = document.selectFirst(".pagination__btn-loader a[href]") != null
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = searchPage(query)
        val base = session().base
        return document.select("a.poster.grid-item").mapNotNull { it.toSearchResult(base) }.distinctBy { it.name.lowercase() to it.type }
    }

    /** Hoster links per episode of one season page (the site's select boxes "ep1", "ep2", …). */
    private fun episodesOf(document: Document): List<Pair<Int, List<String>>> =
        document.select("select[name=pmovie__select-items]")
            .filter { it.id().startsWith("ep") }
            .mapNotNull { select ->
                val number = select.id().takeLastWhile { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
                val links = select.select("option").map { it.attr("value").trim() }.filter { it.startsWith("http") || it.startsWith("//") }
                    .map { if (it.startsWith("//")) "https:$it" else it }
                if (links.isEmpty()) null else number to links
            }

    override suspend fun load(url: String): LoadResponse {
        val path = try { URI(url).rawPath } catch (e: Exception) { url }
        val document = page(path)
        val base = session().base
        val title = document.selectFirst("div.page__subcols.d-flex h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        // The original (mostly English) title: MediaServer matches IMDb ids with it.
        val originalTitle = document.selectFirst("div.pmovie__original-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val description = document.selectFirst("div.page__text")?.text()?.trim()
        val poster = image(base, document.selectFirst("div.pmovie__poster img")?.attr("data-src"))
        val year = document.selectFirst("div.pmovie__year")?.text()?.let { yearOf(it) }
        val genres = document.selectFirst("div.pmovie__genres")?.text()?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val trailer = document.select("link[itemprop=embedUrl]").attr("href").takeIf { it.isNotBlank() }
        val related = document.select("section.pmovie__related a.poster").mapNotNull { it.toSearchResult(base) }
        val ownEpisodes = episodesOf(document)
        val isSeries = genres.firstOrNull() == "Serien" || ownEpisodes.isNotEmpty() || seasonOf(title) != null
        val tags = genres.filter { it != "Filme" && it != "Serien" }

        if (!isSeries) {
            val links = document.select("div.pmovie__player iframe")
                .map { it.attr("src").ifBlank { it.attr("data-src") }.trim() }
                .filter { it.isNotBlank() && it != "about:blank" }
                .map { if (it.startsWith("//")) "https:$it" else it }
                .distinct()
            return newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
                originalTitle?.let { syncData[ORIGINAL_TITLE_KEY] = it }
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = related
                addTrailer(trailer)
            }
        }

        val seriesTitle = withoutSeason(title)
        val season = seasonOf(title) ?: 1
        val seasons = sortedMapOf(season to ownEpisodes)
        // The other seasons of the series are separate pages: found through the site search.
        try {
            val others = searchPage(seriesTitle).select("a.poster.grid-item")
                .mapNotNull { a ->
                    val name = a.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                    val number = seasonOf(name) ?: return@mapNotNull null
                    if (number == season || !sameSeries(withoutSeason(name), seriesTitle)) return@mapNotNull null
                    number to a.attr("href")
                }
                .distinctBy { it.first }
                .take(MAX_SEASONS)
            // A few at a time: many parallel requests make the site answer with its token page.
            others.chunked(3).forEach { chunk ->
                chunk.amap { (number, href) ->
                    try {
                        val other = page(URI(href).rawPath ?: href)
                        synchronized(seasons) { seasons[number] = episodesOf(other) }
                    } catch (e: Throwable) {
                        // A season page that fails is left out.
                    }
                }
            }
        } catch (e: Throwable) {
            // Search unavailable: this season only.
        }
        val episodes = seasons.flatMap { (s, list) ->
            list.map { (number, links) ->
                newEpisode(links.toJson()) {
                    this.season = s
                    this.episode = number
                    this.name = "Episode $number"
                    this.posterUrl = poster
                }
            }
        }
        return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
            syncData[ORIGINAL_TITLE_KEY] = withoutSeason(originalTitle ?: seriesTitle)
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.recommendations = related
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = try { parseJson<Links>(data) } catch (e: Exception) { Links() }
        val referer = "${session().base}/"
        links.amap { link ->
            try {
                loadExtractor(link, referer, subtitleCallback, callback)
            } catch (e: Throwable) {
                // A failing hoster does not stop the others.
            }
        }
        return links.isNotEmpty()
    }

    class Links : ArrayList<String>()

    companion object {
        const val ORIGINAL_TITLE_KEY = "mediaserver.originalTitle"
        private const val TOKEN_LIFETIME_MS = 10 * 60 * 1000L
        private const val ATTEMPTS = 4
        private const val MAX_SEASONS = 30
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8",
        )
        private val SEASON = Regex("""\s*[-–:(]?\s*(?:(\d{1,2})\.?\s*(?:Staffel|Season)|(?:Staffel|Season)\s*(\d{1,2}))\s*\)?\s*$""", RegexOption.IGNORE_CASE)

        fun baseOf(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

        /** The last four-digit year in a text ("United Kingdom, 1962, 110 min" → 1962). */
        fun yearOf(text: String): Int? = Regex("""\b(19\d\d|20\d\d)\b""").findAll(text).lastOrNull()?.value?.toIntOrNull()

        /** "Brothers - 1 Staffel" → 1, "Dark Staffel 2" → 2, else null. */
        fun seasonOf(title: String): Int? {
            val m = SEASON.find(title) ?: return null
            if (m.range.first == 0) return null
            return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
        }

        /** The series name without a season suffix. */
        fun withoutSeason(title: String): String {
            val m = SEASON.find(title) ?: return title
            return if (m.range.first == 0) title else title.substring(0, m.range.first).trim()
        }

        private fun normalized(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun sameSeries(a: String, b: String) = normalized(a) == normalized(b)
    }
}
