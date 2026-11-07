package com.example.animetv

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.Uri
import android.webkit.WebSettings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private val background = Color(0xFF101018)
private val accent = Color(0xFFE73B58)
data class Anime(val id: String, val title: String, val description: String = "")
data class Episode(val id: String, val title: String)
data class StreamServer(val label: String, val apiId: String)
private val candidateServers = listOf(StreamServer("HD-1", "hd-1"), StreamServer("Vidstream-2", "vidstream-2"), StreamServer("ZokoAnime", "zokoanime"))

/** HiAnime-compatible API client.
 * Set animeApiUrl to an authorized HTTPS API deployment root.
 * Supported routes: /api/v2/hianime/home, /search, /anime/{id}/episodes,
 * and /episode/sources. WBRK-dev's Rust API uses different endpoints;
 * see README for compatibility and provider limitations.
 */
class AnimeApi {
    private val client = OkHttpClient()
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')
    private val prefix = "/api/v2/hianime"
    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        require(base.startsWith("https://") && !base.contains("example.invalid")) {
            "Set the ANIME_API_URL GitHub Actions variable to your HTTPS HiAnime-compatible API"
        }
        client.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
            require(response.isSuccessful) { "API HTTP ${response.code} on $path" }
            JSONObject(response.body?.string() ?: "{}")
        }
    }
    private fun data(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json
    private fun animeList(arr: org.json.JSONArray?): List<Anime> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optJSONObject(index)?.let { item ->
                Anime(item.optString("id"), item.optString("name").ifBlank { item.optString("title") },
                    item.optString("description").ifBlank { item.optString("discription") })
            }
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }
    suspend fun connectionCheck(): String = withContext(Dispatchers.IO) {
        val target = "https://aniwatchtv.ws/"
        try {
            client.newCall(Request.Builder().url(target).header("User-Agent", "CAAnime/0.1 (Android TV)").build()).execute().use { response ->
                val type = response.header("Content-Type") ?: "unknown"
                val finalHost = response.request.url.host
                "Website: HTTP ${response.code}; type=$type; host=$finalHost. This checks reachability only, not catalog or streaming access."
            }
        } catch (e: Exception) { "Website check failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}" }
    }
    /** Diagnostic only: inspect public page links without relying on undocumented APIs. */
    suspend fun inspectCatalog(): String = withContext(Dispatchers.IO) {
        val target = "https://aniwatchtv.ws/"
        try {
            client.newCall(Request.Builder().url(target)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val contentType = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful) return@withContext "Catalog check: HTTP ${response.code}"
                if (!contentType.contains("html", ignoreCase = true)) return@withContext "Catalog check: unexpected content type $contentType"
                val links = Regex("""href\\s*=\\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(body).map { it.groupValues[1] }
                    .filter { it.contains("/watch/") || it.contains("/anime/") }
                    .distinct().take(8).toList()
                val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(body)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty().take(80)
                buildString {
                    append("Catalog diagnostic: HTTP ${response.code}; HTML chars=${body.length}; title=$title")
                    append("; matching links=${links.size} (first 8 max)")
                    if (links.isEmpty()) append(". No watch/anime links found in initial HTML; content may be JavaScript-rendered.")
                    else links.forEach { append("\\n"); append(it.take(120)) }
                }
            }
        } catch (e: Exception) { "Catalog check failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}" }
    }
    private suspend fun websitePage(path: String): Document = withContext(Dispatchers.IO) {
        val url = "https://aniwatchtv.ws" + path
        client.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            .build()).execute().use { response ->
            require(response.isSuccessful) { "Website HTTP ${response.code}" }
            val html = response.body?.string().orEmpty()
            require(html.isNotBlank()) { "Website returned an empty page" }
            Jsoup.parse(html, response.request.url.toString())
        }
    }
    private fun extractWebsiteAnime(document: Document): List<Anime> {
        val result = linkedMapOf<String, Anime>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("abs:href")
            val path = runCatching { java.net.URI(href).path.orEmpty() }.getOrDefault("")
            val id = when {
                path.startsWith("/watch/") -> path.removePrefix("/watch/")
                path.startsWith("/anime/") -> path.removePrefix("/anime/")
                else -> ""
            }
            if (id.isBlank()) return@forEach
            val card = anchor.closest(".flw-item, .film_list-wrap, .film-detail, .swiper-slide, .deslide-item, .item") ?: anchor
            val name = anchor.attr("title").ifBlank {
                card.selectFirst(".film-name, .dynamic-name, .desi-head-title, .film-title, img[alt]")?.let {
                    it.attr("title").ifBlank { it.attr("alt").ifBlank { it.text() } }
                }.orEmpty()
            }.ifBlank { anchor.selectFirst("img[alt]")?.attr("alt").orEmpty() }
                .ifBlank { anchor.text() }.trim()
            if (name.isNotBlank()) result.putIfAbsent(id, Anime(id, name))
        }
        return result.values.toList()
    }
    private fun extractEmbeddedAnime(document: Document): List<Anime> {
        val results = linkedMapOf<String, Anime>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 14 || results.size >= 150) return
            when (value) {
                is JSONObject -> {
                    val id = value.optString("id").ifBlank { value.optString("slug") }
                    val name = value.optString("name").ifBlank { value.optString("title") }
                    if (id.matches(Regex("[a-zA-Z0-9_-]+-[0-9]+")) && name.length in 2..160)
                        results.putIfAbsent(id, Anime(id, name, value.optString("description")))
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()), depth + 1)
                }
                is org.json.JSONArray -> for (i in 0 until minOf(value.length(), 400)) visit(value.opt(i), depth + 1)
            }
        }
        document.select("script#__NEXT_DATA__, script[type=application/json]").forEach { script ->
            runCatching { visit(org.json.JSONTokener(script.data()).nextValue(), 0) }
        }
        return results.values.toList()
    }
    private fun websiteAnime(document: Document): List<Anime> =
        (extractWebsiteAnime(document) + extractEmbeddedAnime(document)).distinctBy { it.id }
    private val configuredApi get() = base.startsWith("https://") && !base.contains("example.invalid")
    suspend fun home(): List<Anime> {
        if (!configuredApi) {
            val homepage = websitePage("/home")
            val found = websiteAnime(homepage)
            if (found.isNotEmpty()) return found
            val root = websiteAnime(websitePage("/"))
            if (root.isNotEmpty()) return root
            val nextData = homepage.selectFirst("script#__NEXT_DATA__") != null
            val flight = homepage.html().contains("__next_f.push")
            throw IllegalStateException("No anime found in HTML or embedded JSON (Next data=$nextData, flight=$flight). Catalog may load dynamically.")
        }
        val json = data(get("$prefix/home"))
        val spotlight = animeList(json.optJSONArray("spotlightAnimes") ?: json.optJSONArray("spotlight"))
        val trending = animeList(json.optJSONArray("trendingAnimes") ?: json.optJSONArray("trending"))
        val latest = animeList(json.optJSONArray("latestEpisodeAnimes") ?: json.optJSONArray("latest_episodes"))
        return (spotlight + trending + latest).distinctBy { it.id }
    }
    suspend fun search(q: String): List<Anime> {
        if (!configuredApi) {
            val results = websiteAnime(websitePage("/search?keyword=${enc(q)}"))
            if (results.isEmpty()) throw IllegalStateException("Search returned no parseable anime; data may load dynamically")
            return results
        }
        val json = data(get("$prefix/search?q=${enc(q)}"))
        return animeList(json.optJSONArray("animes") ?: json.optJSONArray("items"))
    }
    private suspend fun websiteEpisodes(animeId: String): List<Episode> = withContext(Dispatchers.IO) {
        val numericId = Regex("(\\d+)$").find(animeId)?.value
            ?: throw IllegalArgumentException("Anime has no numeric identifier: $animeId")
        val candidates = listOf(
            "/api/theme/episode/list/$numericId",
            "/ajax/v2/episode/list/$numericId"
        )
        val errors = mutableListOf<String>()
        for (path in candidates) {
            try {
                val request = Request.Builder().url("https://aniwatchtv.ws$path")
                    .header("Referer", "https://aniwatchtv.ws/watch/$animeId")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                val html = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val raw = response.body?.string().orEmpty()
                    if (raw.trimStart().startsWith("{")) JSONObject(raw).optString("html") else raw
                }
                if (html.isBlank()) throw IllegalStateException("Empty episode HTML")
                val doc = Jsoup.parse(html, "https://aniwatchtv.ws")
                val found = doc.select("a[data-id], a[href*='?ep='], a[href*='&ep=']").mapIndexedNotNull { index, node ->
                    val episodeId = node.attr("data-id").ifBlank {
                        Regex("[?&]ep=(\\d+)").find(node.attr("href"))?.groupValues?.get(1).orEmpty()
                    }
                    if (episodeId.isBlank()) return@mapIndexedNotNull null
                    val number = node.attr("data-number").toIntOrNull()
                        ?: node.selectFirst(".ssli-order, .ep-number")?.text()?.trim()?.toIntOrNull()
                        ?: index + 1
                    val title = node.attr("title").ifBlank {
                        node.selectFirst(".ep-name, .e-dynamic-name")?.text().orEmpty()
                    }.ifBlank { "Episode $number" }
                    Episode(episodeId, title)
                }.distinctBy { it.id }
                if (found.isNotEmpty()) return@withContext found
                errors.add("$path: no episode entries")
            } catch (e: Exception) {
                errors.add("$path: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        throw IllegalStateException("Episode endpoints unavailable: ${errors.joinToString("; ").take(240)}")
    }
    suspend fun episodes(id: String): List<Episode> {
        if (!configuredApi) return websiteEpisodes(id)
        val json = data(get("$prefix/anime/${enc(id)}/episodes"))
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optJSONObject(index)?.let { e ->
                Episode(e.optString("episodeId").ifBlank { e.optString("episode_id").ifBlank { e.optString("id") } },
                    e.optString("title").ifBlank { "Episode ${e.optInt("number", index + 1)}" })
            }
        }.filter { it.id.isNotBlank() }
    }
    suspend fun websiteServers(episodeId: String): String = withContext(Dispatchers.IO) {
        val paths = listOf(
            "/api/theme/episode/servers?episodeId=${enc(episodeId)}",
            "/ajax/v2/episode/servers?episodeId=${enc(episodeId)}"
        )
        val errors = mutableListOf<String>()
        for (path in paths) {
            try {
                val request = Request.Builder().url("https://aniwatchtv.ws$path")
                    .header("Referer", "https://aniwatchtv.ws/")
                    .header("X-Requested-With", "XMLHttpRequest").build()
                val html = client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val raw = response.body?.string().orEmpty()
                    if (raw.trimStart().startsWith("{")) JSONObject(raw).optString("html") else raw
                }
                val doc = Jsoup.parse(html)
                val servers = doc.select(".server-item[data-id], [data-server-id], [data-hash]").mapNotNull { node ->
                    val id = node.attr("data-id").ifBlank { node.attr("data-server-id") }
                    val name = node.text().trim()
                    val hash = node.attr("data-hash")
                    if (name.isBlank() || (id.isBlank() && hash.isBlank())) null
                    else "$name (ID ${id.ifBlank { "none" }}, embed=${hash.isNotBlank()})"
                }.distinct()
                if (servers.isNotEmpty()) return@withContext "Servers: " + servers.joinToString(" | ")
                errors.add("$path: no server entries")
            } catch (e: Exception) { errors.add("$path: ${e.message ?: "request failed"}") }
        }
        "Server lookup unsuccessful: " + errors.joinToString("; ").take(220)
    }
    private suspend fun websiteStream(episodeId: String, server: String, category: String): String = withContext(Dispatchers.IO) {
        val path = "/api/theme/episode/servers?episodeId=${enc(episodeId)}"
        val request = Request.Builder().url("https://aniwatchtv.ws$path")
            .header("Referer", "https://aniwatchtv.ws/")
            .header("X-Requested-With", "XMLHttpRequest").build()
        val raw = client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Server list HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val html = if (raw.trimStart().startsWith("{")) JSONObject(raw).optString("html") else raw
        val requested = server.lowercase().replace("-", "").replace(" ", "")
        val entry = Jsoup.parse(html).select("[data-hash]").firstOrNull { node ->
            node.text().lowercase().replace("-", "").replace(" ", "").contains(requested)
        } ?: throw IllegalStateException("Selected server is unavailable; try ZokoAnime")
        val hash = entry.attr("data-hash")
        val decoded = try {
            String(android.util.Base64.decode(hash, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { throw IllegalStateException("Invalid server embed reference") }
        val uri = runCatching { java.net.URI(decoded) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank())
            throw IllegalStateException("Server returned an invalid embed URL")
        decoded
    }
    suspend fun stream(id: String, server: String, category: String): String {
        if (!configuredApi) return websiteStream(id, server, category)
        val json = data(get("$prefix/episode/sources?animeEpisodeId=${enc(id)}&server=${enc(server)}&category=${enc(category)}"))
        val sources = json.optJSONArray("sources")
        require(sources != null && sources.length() > 0) { "No playable sources returned" }
        return sources.getJSONObject(0).getString("url")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AnimeApp() }
    }
}

@Composable
fun AnimeApp() {
    val api = remember { AnimeApi() }
    var screen by remember { mutableStateOf("home") }
    var selected by remember { mutableStateOf<Anime?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var chosenServer by remember { mutableStateOf(candidateServers.first()) }
    var chosenCategory by remember { mutableStateOf("sub") }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Anime>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(playing != null || screen != "home") {
        if (playing != null) playing = null else screen = "home"
    }
    LaunchedEffect(screen, query, selected, refresh) {
        error = null
        loading = true
        try {
            when (screen) {
                "home" -> list = api.home()
                "search" -> list = if (query.isBlank()) emptyList() else api.search(query)
                "details" -> episodes = selected?.let { api.episodes(it.id) } ?: emptyList()
            }
        } catch (e: Exception) { error = e.message ?: "Unable to load content" }
        finally { loading = false }
    }
    if (playing != null) {
        VideoScreen(api, playing!!, chosenServer.apiId, chosenCategory, onBack = { playing = null })
        return
    }
    Column(Modifier.fillMaxSize().background(background).verticalScroll(rememberScrollState()).padding(36.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CAANIME", color = accent, fontSize = 28.sp, fontWeight = FontWeight.Black)
            TvButton("Home") { screen = "home" }
            TvButton("Search") { screen = "search" }
            TvButton("Refresh") { refresh++ }
            TvButton("Inspect catalog") {
                scope.launch {
                    checking = true
                    diagnostic = api.inspectCatalog()
                    checking = false
                }
            }
            TvButton("Check website") {
                scope.launch {
                    checking = true
                    diagnostic = api.connectionCheck()
                    checking = false
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (screen == "search") {
            BasicTextField(query, onValueChange = { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth().background(Color.DarkGray, RoundedCornerShape(8.dp)).padding(18.dp),
                decorationBox = { inner -> Column { if (query.isEmpty()) Text("Search anime…", color = Color.LightGray); inner() } })
            Spacer(Modifier.height(24.dp))
        }
        if (checking) Text("Checking website…", color = Color.LightGray)
        diagnostic?.let { Text(it, color = Color.LightGray) }
        if (loading) LinearProgressIndicator(color = accent)
        if (error != null) Text("Connection: $error", color = Color(0xFFFFB4AB))
        when (screen) {
            "details" -> {
                Text(selected?.title ?: "Anime", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(selected?.description ?: "", color = Color.LightGray)
                Spacer(Modifier.height(22.dp))
                Text("Playback options", color = Color.White, fontSize = 24.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    candidateServers.forEach { server ->
                        TvButton((if (chosenServer == server) "✓ " else "") + server.label) { chosenServer = server }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("sub", "dub").forEach { category ->
                        TvButton((if (chosenCategory == category) "✓ " else "") + category.uppercase()) { chosenCategory = category }
                    }
                }
                Spacer(Modifier.height(22.dp))
                if (episodes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    TvButton("Find episode servers") {
                        scope.launch {
                            checking = true
                            diagnostic = api.websiteServers(episodes.first().id)
                            checking = false
                        }
                    }
                }
                Text("Episodes", color = Color.White, fontSize = 24.sp)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(episodes) { episode -> TvButton(episode.title.ifBlank { episode.id }) { playing = episode.id } }
                }
            }
            else -> {
                Text(if (screen == "home") "Discover anime" else "Results", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(list) { anime -> TvButton(anime.title) { selected = anime; screen = "details" } }
                }
                if (!loading && list.isEmpty() && error == null) Text("No titles to display", color = Color.LightGray)
            }
        }
    }
}

@Composable
fun TvButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.border(if (focused) 3.dp else 1.dp, if (focused) accent else Color.Gray, RoundedCornerShape(10.dp))
        .background(if (focused) Color(0xFF3C2532) else Color(0xFF24242F), RoundedCornerShape(10.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Text(label, color = Color.White, fontSize = 19.sp)
    }
}

private val blockedAdHosts = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adnxs.com", "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "exoclick.com", "exosrv.com")
private val dismissPlayerOverlays = "(function(){var n=document.querySelectorAll(\"[class*=advert],[id*=advert],[class*=popup],[id*=popup],[class*=modal],[id*=modal],[class*=overlay],[id*=overlay]\");n.forEach(function(e){if(e.querySelector(\"video\"))return;var r=e.getBoundingClientRect();var p=getComputedStyle(e).position;if(r.width>120&&r.height>100&&(p===\"fixed\"||p===\"absolute\"))e.style.setProperty(\"display\",\"none\",\"important\")})})();"

private val playerCosmeticFilter = "(function(){if(document.getElementById(\"caanime-ad-style\"))return;var s=document.createElement(\"style\");s.id=\"caanime-ad-style\";s.textContent=\"iframe[src*=\\\"doubleclick.net\\\"],iframe[src*=\\\"googlesyndication.com\\\"],iframe[src*=\\\"exoclick.com\\\"],iframe[src*=\\\"exosrv.com\\\"],[id^=\\\"google_ads_\\\"],.adsbygoogle,[data-ad-client],[data-ad-slot]{display:none!important;visibility:hidden!important;pointer-events:none!important;}\";(document.head||document.documentElement).appendChild(s);})();"

private fun isKnownAdHost(host: String?): Boolean {
    val h = host?.lowercase()?.trimEnd('.') ?: return false
    return blockedAdHosts.any { h == it || h.endsWith(".$it") }
}

@Composable
fun VideoScreen(api: AnimeApi, episodeId: String, server: String, category: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var url by remember(episodeId, server, category) { mutableStateOf<String?>(null) }
    var error by remember(episodeId, server, category) { mutableStateOf<String?>(null) }
    var loading by remember(episodeId, server, category) { mutableStateOf(true) }
    var adDiagnostic by remember(episodeId, server, category) { mutableStateOf<String?>(null) }
    var fullscreen by remember(episodeId, server, category) { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val chromeRef = remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val fullscreenRef = remember { mutableStateOf<View?>(null) }
    val containerRef = remember { mutableStateOf<FrameLayout?>(null) }

    LaunchedEffect(episodeId, server, category) {
        try {
            url = api.stream(episodeId, server, category)
        } catch (e: Exception) {
            error = e.message ?: "Unable to resolve playback"
            loading = false
        }
    }
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    val isEmbed = url?.let { !it.contains(".m3u8", true) && !it.contains(".mp4", true) } ?: false
    LaunchedEffect(url) {
        url?.takeUnless { isEmbed }?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.playWhenReady = true
            loading = false
        }
    }
    fun exitFullscreen() {
        chromeRef.value?.onCustomViewHidden()
        chromeRef.value = null
        fullscreenRef.value = null
        fullscreen = false
    }
    BackHandler { if (fullscreen) exitFullscreen() else onBack() }
    DisposableEffect(episodeId, server, category) {
        onDispose {
            chromeRef.value?.onCustomViewHidden()
            chromeRef.value = null
            containerRef.value?.removeAllViews()
            webViewRef.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef.value = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isEmbed && url != null) {
            val embedUrl = url!!
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        containerRef.value = this
                        val browser = WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(true)
                            setBackgroundColor(android.graphics.Color.BLACK)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    if (!isKnownAdHost(request?.url?.host)) return null
                                    return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    if (request == null || !request.isForMainFrame) return false
                                    val target = request.url
                                    val embedHost = Uri.parse(embedUrl).host
                                    return isKnownAdHost(target.host) || target.scheme !in listOf("https", "http") ||
                                        !target.host.equals(embedHost, ignoreCase = true)
                                }
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(playerCosmeticFilter, null)
                                    loading = false
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                                    if (request?.isForMainFrame == true) {
                                        error = "Player page failed to load: ${failure?.description ?: "network error"}"
                                        loading = false
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    if (view == null || fullscreenRef.value != null) {
                                        callback?.onCustomViewHidden()
                                        return
                                    }
                                    fullscreenRef.value = view
                                    chromeRef.value = callback
                                    fullscreen = true
                                    this@apply.addView(view, FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    ))
                                    browserFocus(view)
                                }
                                override fun onHideCustomView() {
                                    fullscreenRef.value?.let { this@apply.removeView(it) }
                                    fullscreenRef.value = null
                                    chromeRef.value = null
                                    fullscreen = false
                                }
                            }
                            loadUrl(embedUrl)
                        }
                        webViewRef.value = browser
                        addView(browser, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (url != null) {
            AndroidView(factory = { PlayerView(it).apply {
                this.player = player
                useController = true
            } }, modifier = Modifier.fillMaxSize())
        }
        if (loading && error == null) {
            Text("Loading player…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        error?.let {
            Text(it.take(240), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        if (!fullscreen) {
            adDiagnostic?.let { status ->
                Text(
                    status,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(Color(0xCC222222)).padding(12.dp)
                )
            }
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvButton("Back") { onBack() }
                if (isEmbed) {
                    TvButton("Hide ad overlay") {
                        webViewRef.value?.evaluateJavascript(
                            """(function(){
 var found=0, hidden=0, frames=0, accessible=0, blocked=0;
 var selector="[class*=advert],[id*=advert],[class*=popup],[id*=popup],[class*=modal],[id*=modal],[class*=overlay],[id*=overlay],[class*=banner],[id*=banner],[class*=interstitial],[id*=interstitial]";
 function scan(doc,depth){
  if(!doc||depth>4)return;
  var win=doc.defaultView;
  if(!win)return;
  var nodes=doc.querySelectorAll(selector);
  nodes.forEach(function(el){
   found++;
   if(el.closest("video")||el.querySelector("video"))return;
   var rect=el.getBoundingClientRect();
   var st=win.getComputedStyle(el);
   if(rect.width<150||rect.height<100||st.display==="none"||st.visibility==="hidden")return;
   var label=((el.id||"")+" "+(typeof el.className==="string"?el.className:"")).toLowerCase();
   var named=/advert|popup|modal|overlay|interstitial|banner/.test(label);
   var positioned=st.position==="fixed"||st.position==="absolute"||Number(st.zIndex)>100;
   if(!named||!positioned)return;
   if(el.querySelector("iframe")&&rect.width>win.innerWidth*0.85&&rect.height>win.innerHeight*0.85)return;
   el.style.setProperty("display","none","important");hidden++;
  });
  doc.querySelectorAll("iframe").forEach(function(frame){
   frames++;
   try{var child=frame.contentDocument;if(child&&child.documentElement){accessible++;scan(child,depth+1);}else blocked++;}catch(e){blocked++;}
  });
 }
 scan(document,0);
 return "Found "+found+"; hidden "+hidden+"; frames "+frames+"; accessible "+accessible+"; blocked "+blocked;
})();"""
                        ) { result ->
                            adDiagnostic = result.trim('"').replace("\\u003d", "=")
                        }
                    }
                }
            }
        }
    }
}

private fun browserFocus(view: View) {
    view.isFocusableInTouchMode = true
    view.requestFocus()
}
