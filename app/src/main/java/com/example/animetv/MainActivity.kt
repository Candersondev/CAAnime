package com.example.animetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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
    suspend fun home(): List<Anime> {
        val json = data(get("$prefix/home"))
        val spotlight = animeList(json.optJSONArray("spotlightAnimes") ?: json.optJSONArray("spotlight"))
        val trending = animeList(json.optJSONArray("trendingAnimes") ?: json.optJSONArray("trending"))
        val latest = animeList(json.optJSONArray("latestEpisodeAnimes") ?: json.optJSONArray("latest_episodes"))
        return (spotlight + trending + latest).distinctBy { it.id }
    }
    suspend fun search(q: String): List<Anime> {
        val json = data(get("$prefix/search?q=${enc(q)}"))
        return animeList(json.optJSONArray("animes") ?: json.optJSONArray("items"))
    }
    suspend fun episodes(id: String): List<Episode> {
        val json = data(get("$prefix/anime/${enc(id)}/episodes"))
        val arr = json.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optJSONObject(index)?.let { e ->
                Episode(e.optString("episodeId").ifBlank { e.optString("episode_id").ifBlank { e.optString("id") } },
                    e.optString("title").ifBlank { "Episode ${e.optInt("number", index + 1)}" })
            }
        }.filter { it.id.isNotBlank() }
    }
    suspend fun stream(id: String, server: String, category: String): String {
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
    Column(Modifier.fillMaxSize().background(background).padding(36.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CAANIME", color = accent, fontSize = 28.sp, fontWeight = FontWeight.Black)
            TvButton("Home") { screen = "home" }
            TvButton("Search") { screen = "search" }
            TvButton("Refresh") { refresh++ }
        }
        Spacer(Modifier.height(28.dp))
        if (screen == "search") {
            BasicTextField(query, onValueChange = { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth().background(Color.DarkGray, RoundedCornerShape(8.dp)).padding(18.dp),
                decorationBox = { inner -> Column { if (query.isEmpty()) Text("Search anime…", color = Color.LightGray); inner() } })
            Spacer(Modifier.height(24.dp))
        }
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

@Composable
fun VideoScreen(api: AnimeApi, episodeId: String, server: String, category: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var url by remember(episodeId, server, category) { mutableStateOf<String?>(null) }
    var error by remember(episodeId) { mutableStateOf<String?>(null) }
    LaunchedEffect(episodeId, server, category) {
        try { url = api.stream(episodeId, server, category) } catch (e: Exception) { error = e.message }
    }
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(url) { url?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare(); player.playWhenReady = true } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
        if (url == null) Text(error ?: "Loading video…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.TopStart).padding(20.dp)) { TvButton("Back") { onBack() } }
    }
}
