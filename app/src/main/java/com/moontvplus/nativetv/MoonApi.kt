package com.moontvplus.nativetv

import android.content.Context
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class SiteConfig(val name: String, val version: String, val storageType: String, val turnstile: Boolean)
data class VideoItem(
    val source: String,
    val id: String,
    val title: String,
    val poster: String,
    val year: String,
    val sourceName: String,
    val episodes: List<String> = emptyList(),
    val episodeTitles: List<String> = emptyList(),
    val proxyMode: Boolean = false,
    val category: String = "",
    val description: String = "",
    val remarks: String = "",
    val rate: String = ""
)
data class QrSession(val token: String, val url: String, val expiresAt: Long)

class ApiException(val code: Int, message: String) : Exception(message)

class MoonApi(context: Context) {
    private val prefs = context.getSharedPreferences("moon_session", Context.MODE_PRIVATE)
    init { prefs.edit().remove("auth").apply() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                if (!isOwnServer(url.toString()) || authCookie.isBlank()) return emptyList()
                return listOf(Cookie.Builder().name("auth").value(authCookie).hostOnlyDomain(url.host).path("/").build())
            }
        })
        .build()

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        private set(value) { prefs.edit().putString("base_url", value).apply() }

    var authCookie: String
        get() = prefs.getString("auth_encrypted", "")?.takeIf { it.isNotBlank() }?.let(SessionCipher::decrypt) ?: ""
        private set(value) {
            prefs.edit().remove("auth")
                .putString("auth_encrypted", if (value.isBlank()) "" else SessionCipher.encrypt(value)).apply()
        }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        private set(value) { prefs.edit().putString("username", value).apply() }

    fun setServer(url: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://"))
        if (normalized != baseUrl) {
            baseUrl = normalized
            authCookie = ""
            username = ""
        }
    }

    fun clearLogin() { authCookie = ""; username = "" }
    private fun isOwnServer(url: String): Boolean {
        val own = baseUrl.toHttpUrlOrNull() ?: return false
        val target = url.toHttpUrlOrNull() ?: return false
        return own.scheme == target.scheme && own.host == target.host && own.port == target.port
    }

    fun mediaClient(): OkHttpClient = client.newBuilder().followRedirects(true).build()
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context.applicationContext).okHttpClient(mediaClient()).build()
    }
    fun absolute(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + if (path.startsWith('/')) path else "/$path"
    fun imageUrl(path: String): String {
        val url = absolute(path)
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return url
        val needsProxy = host == "doubanio.com" || host.endsWith(".doubanio.com") ||
            host == "bgm.tv" || host.endsWith(".bgm.tv") ||
            host == "bangumi.tv" || host.endsWith(".bangumi.tv") ||
            host == "bangumi.lol" || host.endsWith(".bangumi.lol")
        if (!needsProxy) return url
        val source = if (host.contains("doubanio.com")) "" else "&source=bangumi"
        return "$baseUrl/api/image-proxy?url=${enc(url)}$source"
    }
    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        requestOnce(path, method, body, true)
    }

    private suspend fun requestOnce(path: String, method: String, body: JSONObject?, refreshOn401: Boolean): String {
        val serverAtStart = baseUrl
        val requestUrl = absolute(path)
        val builder = Request.Builder().url(requestUrl)
            .header("User-Agent", "MoonTVPlus App NativeTV/0.1")
            .header("Accept", "application/json")
        if (method == "POST") builder.post((body?.toString() ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (method == "DELETE") builder.delete()
        val response = suspendCancellableCoroutine<okhttp3.Response> { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
        return response.use {
            val cookie = it.headers.values("Set-Cookie").firstOrNull { value -> value.startsWith("auth=") }
            if (cookie != null && serverAtStart == baseUrl && isOwnServer(requestUrl)) {
                authCookie = cookie.substringAfter("auth=").substringBefore(';')
                if (authCookie.isNotBlank()) username = userFromCookie(authCookie)
            }
            val text = it.body?.string() ?: ""
            if (it.code == 401 && refreshOn401 && serverAtStart == baseUrl && path != "/api/auth/refresh" && path != "/api/login") {
                try {
                    requestOnce("/api/auth/refresh", "POST", null, false)
                    return@use requestOnce(path, method, body, false)
                } catch (_: Exception) { clearLogin() }
            }
            if (!it.isSuccessful) throw ApiException(it.code, text.take(160))
            text
        }
    }

    suspend fun probe(rawUrl: String): Long {
        val url = absolute(rawUrl)
        val builder = Request.Builder().url(url).header("Range", "bytes=0-131071")
        val request = builder.build()
        val start = System.nanoTime()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            if (continuation.isActive) continuation.resumeWithException(ApiException(it.code, "测速失败"))
                            return
                        }
                        val bytes = ByteArray(131072)
                        val read = it.body?.byteStream()?.read(bytes) ?: -1
                        val elapsed = (System.nanoTime() - start).coerceAtLeast(1)
                        if (continuation.isActive) continuation.resume((read.coerceAtLeast(0).toLong() * 1_000_000_000L) / elapsed)
                    }
                }
            })
        }
    }

    suspend fun probeMedia(rawUrl: String): Long {
        val url = absolute(rawUrl)
        if (!url.contains(".m3u", true)) return probe(url)
        var current = url
        repeat(2) {
            val manifest = request(current)
            val first = manifest.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                ?: return probe(current)
            current = java.net.URI(current).resolve(first).toString()
            if (!current.contains(".m3u", true)) return probe(current)
        }
        return probe(current)
    }

    private fun userFromCookie(value: String): String = try {
        var decoded = value
        repeat(2) { if (decoded.contains('%')) decoded = java.net.URLDecoder.decode(decoded, "UTF-8") }
        JSONObject(decoded).optString("username", "default")
    } catch (_: Exception) { "default" }

    suspend fun siteConfig(): SiteConfig {
        val json = JSONObject(request("/api/server-config"))
        if (!json.has("StorageType") || !json.has("Version")) throw IllegalStateException("这不是兼容的 MoonTVPlus 服务")
        return SiteConfig(json.optString("SiteName", "MoonTVPlus"), json.getString("Version"), json.getString("StorageType"), json.optBoolean("LoginRequireTurnstile"))
    }

    suspend fun createQr(): QrSession {
        val data = JSONObject(request("/api/auth/qr/create", "POST"))
        return QrSession(data.getString("token"), data.getString("qrUrl"), data.getLong("expiresAt"))
    }

    suspend fun qrStatus(token: String): String = JSONObject(request("/api/auth/qr/status?token=${enc(token)}")).optString("status")

    suspend fun passwordLogin(user: String, password: String, local: Boolean) {
        val body = JSONObject().put("password", password)
        if (!local) body.put("username", user)
        val json = JSONObject(request("/api/login", "POST", body))
        if (!json.optBoolean("ok") || authCookie.isBlank()) throw IllegalStateException("登录失败")
        if (username.isBlank()) username = if (local) "default" else user
    }

    suspend fun refresh() { request("/api/auth/refresh", "POST") }

    suspend fun sources(): Set<String> {
        val array = JSONObject(request("/api/source-search/sources")).optJSONArray("sources") ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it).getString("key") }.toSet()
    }

    suspend fun discover(kind: String, tag: String): List<VideoItem> {
        val json = JSONObject(request("/api/douban?type=${enc(kind)}&tag=${enc(tag)}&pageSize=20"))
        return parseItems(json.optJSONArray("list") ?: JSONArray())
    }

    suspend fun doubanCategory(kind: String, category: String, type: String): List<VideoItem> {
        val json = JSONObject(request("/api/douban/categories?kind=${enc(kind)}&category=${enc(category)}&type=${enc(type)}&limit=20&start=0"))
        return parseItems(json.optJSONArray("list") ?: JSONArray())
    }

    suspend fun shortDramaRecommendations(): List<VideoItem> {
        val json = JSONObject(request("/api/duanju/recommends"))
        val allowed = sources()
        return parseItems(json.optJSONArray("data") ?: JSONArray()).map { item ->
            if (item.source in allowed) item else item.copy(source = "", id = "", episodes = emptyList(), episodeTitles = emptyList())
        }
    }

    suspend fun bangumiToday(): List<VideoItem> {
        val calendar = JSONArray(request("/api/bangumi/calendar"))
        val weekday = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        val today = (0 until calendar.length()).mapNotNull { calendar.optJSONObject(it) }
            .firstOrNull { it.optJSONObject("weekday")?.optString("en") == weekday }
        val items = today?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val anime = items.optJSONObject(index) ?: return@mapNotNull null
            val title = anime.optString("name_cn").ifBlank { anime.optString("name") }
            if (title.isBlank()) return@mapNotNull null
            val images = anime.optJSONObject("images")
            val poster = listOf("large", "common", "medium", "small", "grid")
                .firstNotNullOfOrNull { key -> images?.optString(key)?.takeIf { it.isNotBlank() } } ?: ""
            VideoItem("", "", title, poster, anime.optString("air_date").take(4), "新番放送")
        }
    }

    suspend fun search(query: String, allowed: Set<String>): List<VideoItem> {
        val json = JSONObject(request("/api/search?q=${enc(query)}"))
        return parseItems(json.optJSONArray("results") ?: JSONArray())
            .filter { it.source in allowed && matchesSearch(query, it.title) }
    }

    suspend fun detail(source: String, id: String): VideoItem {
        return parseItem(JSONObject(request("/api/source-detail?source=${enc(source)}&id=${enc(id)}")))
    }

    suspend fun resolveEpisode(raw: String, source: String, proxyMode: Boolean): String {
        var url = raw
        val lazy = listOf("/api/xiaoya/play", "/api/openlist/play", "/api/netdisk/", "/api/source-script/play")
        if (lazy.any { url.startsWith(it) }) {
            val json = JSONObject(request(url + if (url.contains('?')) "&format=json" else "?format=json"))
            url = json.optString("url", url)
        }
        if (proxyMode && url.contains(".m3u", true) && !url.startsWith("/api/proxy/")) {
            url = "/api/proxy/vod/m3u8?url=${enc(url)}&source=${enc(source)}"
        }
        return absolute(url)
    }

    suspend fun playRecords(): JSONObject = JSONObject(request("/api/playrecords"))
    suspend fun favorites(): JSONObject = JSONObject(request("/api/favorites"))
    suspend fun saveRecord(key: String, record: JSONObject) { request("/api/playrecords", "POST", JSONObject().put("key", key).put("record", record)) }
    suspend fun saveFavorite(key: String, favorite: JSONObject) { request("/api/favorites", "POST", JSONObject().put("key", key).put("favorite", favorite)) }
    suspend fun deleteFavorite(key: String) { request("/api/favorites?key=${enc(key)}", "DELETE") }

    private fun parseItems(array: JSONArray): List<VideoItem> = (0 until array.length()).mapNotNull {
        try { parseItem(array.getJSONObject(it)) } catch (_: Exception) { null }
    }

    private fun parseItem(json: JSONObject): VideoItem {
        val episodes = json.optJSONArray("episodes") ?: JSONArray()
        val titles = json.optJSONArray("episodes_titles") ?: JSONArray()
        return VideoItem(
            json.optString("source"), json.optString("id"), json.optString("title"),
            json.optString("poster"), json.optString("year"), json.optString("source_name"),
            (0 until episodes.length()).map { episodes.optString(it) },
            (0 until titles.length()).map { titles.optString(it) }, json.optBoolean("proxyMode"),
            json.optString("type_name").ifBlank { json.optString("class") },
            json.optString("desc"), json.optString("vod_remarks"),
            json.optString("rate").takeIf { it.toDoubleOrNull()?.let { score -> score > 0 && score <= 10 } == true } ?: ""
        )
    }
}
