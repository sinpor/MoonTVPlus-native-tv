package com.moontvplus.nativetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = MoonApi(this)
        val store = WatchStore(this, api)
        setContent { MaterialTheme { MoonApp(api, store) } }
    }
}

private enum class Screen { SERVER, LOGIN, HOME, SEARCH, DETAIL, SETTINGS }
private enum class HomeTab(val label: String) { HOT("热门推荐"), CONTINUE("继续观看"), FAVORITES("我的收藏") }
private enum class RecommendationCategory(val label: String) {
    MOVIES("热门电影"), SHORT_DRAMAS("热播短剧"), ANIME("新番放送"), SERIES("热门剧集"), VARIETY("热门综艺")
}
private class HomeState {
    var identity = ""
    var category by mutableStateOf(RecommendationCategory.MOVIES)
    var recommendations by mutableStateOf<Map<RecommendationCategory, List<VideoItem>>>(emptyMap())
    var records by mutableStateOf<List<VideoItem>>(emptyList())
    var favorites by mutableStateOf<List<VideoItem>>(emptyList())
    var categoryErrors by mutableStateOf<Map<RecommendationCategory, String>>(emptyMap())
    var categoryLoading by mutableStateOf<Set<RecommendationCategory>>(emptySet())
    var libraryLoading by mutableStateOf(false)
    val categoryGrids = RecommendationCategory.entries.associateWith { LazyGridState() }
    val continueGrid = LazyGridState()
    val favoritesGrid = LazyGridState()

    fun reset(newIdentity: String) {
        identity = newIdentity
        category = RecommendationCategory.MOVIES
        recommendations = emptyMap()
        records = emptyList()
        favorites = emptyList()
        categoryErrors = emptyMap()
        categoryLoading = emptySet()
    }
}
private val background = Color(0xFF090B14)
private val fieldText = Color.White
private val fieldHint = Color(0xFFADB8CD)

@Composable
private fun tvTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = fieldText,
    unfocusedTextColor = fieldText,
    focusedLabelColor = fieldHint,
    unfocusedLabelColor = fieldHint,
    focusedPlaceholderColor = fieldHint,
    unfocusedPlaceholderColor = fieldHint,
    cursorColor = fieldText
)

@Composable
private fun MoonApp(api: MoonApi, store: WatchStore) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(if (api.baseUrl.isBlank()) Screen.SERVER else if (api.authCookie.isBlank()) Screen.LOGIN else Screen.HOME) }
    var site by remember { mutableStateOf<SiteConfig?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VideoItem?>(null) }
    var homeRevision by remember { mutableStateOf(0) }
    var homeTab by remember { mutableStateOf(HomeTab.HOT) }
    val homeState = remember { HomeState() }
    var detailOrigin by remember { mutableStateOf(Screen.HOME) }
    var detailFullScreen by remember { mutableStateOf(false) }

    fun goBack() {
        screen = when (screen) {
            Screen.DETAIL -> if (detailFullScreen) { detailFullScreen = false; Screen.DETAIL } else { homeRevision++; detailOrigin }
            Screen.SEARCH, Screen.SETTINGS -> Screen.HOME
            Screen.HOME -> Screen.HOME
            else -> Screen.SERVER
        }
    }
    BackHandler(screen != Screen.SERVER) { goBack() }

    LaunchedEffect(api.baseUrl) {
        if (api.baseUrl.isNotBlank()) {
            try { site = api.siteConfig(); store.storageType = site!!.storageType } catch (e: Exception) { message = e.message ?: "服务连接失败" }
        }
    }

    LaunchedEffect(screen, api.baseUrl) {
        if (screen == Screen.HOME && api.authCookie.isNotBlank()) {
            try { api.sources() }
            catch (e: ApiException) {
                if (e.code == 401) { api.clearLogin(); message = "登录已失效，请重新扫码"; screen = Screen.LOGIN }
            } catch (_: Exception) { /* HomeScreen shows connection errors. */ }
        }
    }

    Box(Modifier.fillMaxSize().background(background)
        .padding(if (screen == Screen.DETAIL && detailFullScreen) 0.dp else 30.dp)) {
        when (screen) {
            Screen.SERVER -> ServerScreen(api.baseUrl, message, busy, onConnect = { url ->
                scope.launch {
                    busy = true; message = "正在检查服务…"
                    try {
                        api.setServer(url)
                        site = api.siteConfig()
                        store.storageType = site!!.storageType
                        message = ""
                        screen = if (api.authCookie.isBlank()) Screen.LOGIN else Screen.HOME
                    } catch (e: Exception) { message = e.message ?: "无法连接服务" }
                    busy = false
                }
            })
            Screen.LOGIN -> LoginScreen(api, site, message, onLogin = { message = ""; homeRevision++; screen = Screen.HOME }, onError = { message = it }, onServer = { screen = Screen.SERVER })
            Screen.HOME -> HomeScreen(api, store, site, homeState, homeRevision, homeTab, onTabSelected = { homeTab = it }, onSearch = { screen = Screen.SEARCH }, onSelect = { selected = it; detailOrigin = Screen.HOME; detailFullScreen = false; screen = Screen.DETAIL }, onSettings = { screen = Screen.SETTINGS })
            Screen.SEARCH -> SearchScreen(api, onSelect = { selected = it; detailOrigin = Screen.SEARCH; detailFullScreen = false; screen = Screen.DETAIL }, onBack = { screen = Screen.HOME })
            Screen.DETAIL -> selected?.let { item -> DetailPlaybackScreen(api, store, item, detailFullScreen, onFullScreen = { detailFullScreen = true }, onBack = { if (detailFullScreen) detailFullScreen = false else { homeRevision++; screen = detailOrigin } }) }
            Screen.SETTINGS -> SettingsScreen(api, store, site, onBack = { screen = Screen.HOME }, onServer = { api.clearLogin(); screen = Screen.SERVER })
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String = "") {
    Column {
        Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFFADB8CD), fontSize = 16.sp)
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun ServerScreen(current: String, message: String, busy: Boolean, onConnect: (String) -> Unit) {
    var url by remember(current) { mutableStateOf(current) }
    var allowHttp by remember { mutableStateOf(false) }
    val lan = remember { LanAddressInput { url = it } }
    DisposableEffect(lan) { lan.start(); onDispose { lan.stop() } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Heading("连接 MoonTVPlus", "输入你自己部署的服务地址")
        OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, placeholder = { Text("https://example.com") }, singleLine = true, colors = tvTextFieldColors(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        lan.url?.let { link ->
            Text("也可用同一局域网的手机扫码输入服务地址", color = Color.White)
            QrCode(link, Modifier.width(135.dp).height(135.dp))
        } ?: Text("当前网络无法提供手机辅助输入，请使用电视键盘输入。", color = Color(0xFFADB8CD))
        if (url.startsWith("http://")) {
            Text("HTTP 会明文传输登录凭据。请确认这是你信任的网络。", color = Color(0xFFFFBC7A))
            Button(onClick = { allowHttp = !allowHttp }) { Text(if (allowHttp) "已允许 HTTP" else "允许连接 HTTP") }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onConnect(url) }, enabled = !busy && (url.startsWith("https://") || url.startsWith("http://") && allowHttp)) { Text("连接服务") }
        if (message.isNotBlank()) Text(message, color = Color(0xFFFFBC7A))
    }
}

@Composable
private fun LoginScreen(api: MoonApi, site: SiteConfig?, error: String, onLogin: () -> Unit, onError: (String) -> Unit, onServer: () -> Unit) {
    val scope = rememberCoroutineScope()
    var qr by remember { mutableStateOf<QrSession?>(null) }
    var status by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(api.baseUrl) {
        try { qr = api.createQr(); status = "请用手机扫码，在网页上确认电视登录" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError(e.message ?: "无法生成二维码") }
    }
    LaunchedEffect(qr?.token) {
        val session = qr ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            try {
                val next = api.qrStatus(session.token)
                if (next == "confirmed") {
                    if (api.authCookie.isBlank()) onError("服务已确认扫码，但没有发回登录会话。请刷新二维码重试。")
                    else try { api.sources(); onLogin() }
                    catch (e: Exception) { onError("扫码会话验证失败：${e.message ?: "请重试"}") }
                    break
                }
                status = when (next) { "scanned" -> "已扫码，请在手机确认"; "expired" -> "二维码已过期，正在刷新"; "cancelled" -> "已取消，正在刷新"; else -> "等待手机确认" }
                if (next == "expired" || next == "cancelled") { qr = api.createQr(); break }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "查询扫码状态失败") }
        }
    }
    Column {
        Heading("登录 ${site?.name ?: "MoonTVPlus"}", api.baseUrl)
        qr?.let { QrCode(it.url, Modifier.width(245.dp).height(245.dp)) }
        Text(status, color = Color.White)
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8C8C))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { scope.launch { try { qr = api.createQr() } catch (e: Exception) { onError(e.message ?: "刷新失败") } } }) { Text("刷新二维码") }
            Button(onClick = { showPassword = !showPassword }) { Text("账号密码备用登录") }
            Button(onClick = onServer) { Text("更换服务") }
        }
        if (showPassword) {
            if (site?.turnstile == true) Text("此服务启用了人机验证，请在手机网页登录后扫码确认。", color = Color(0xFFFFBC7A))
            else {
                if (site?.storageType != "localstorage") OutlinedTextField(user, { user = it }, label = { Text("用户名") }, colors = tvTextFieldColors())
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = tvTextFieldColors())
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try { api.passwordLogin(user, password, site?.storageType == "localstorage"); onLogin() }
                        catch (e: Exception) { onError(e.message ?: "登录失败") }
                        busy = false
                    }
                }) { Text("登录") }
            }
        }
    }
}

@Composable
private fun HomeScreen(api: MoonApi, store: WatchStore, site: SiteConfig?, state: HomeState, revision: Int, tab: HomeTab, onTabSelected: (HomeTab) -> Unit, onSearch: () -> Unit, onSelect: (VideoItem) -> Unit, onSettings: () -> Unit) {
    val identity = "${api.baseUrl}|${api.username}"
    LaunchedEffect(identity, state.category) {
        if (state.identity != identity) state.reset(identity)
        val category = state.category
        if (category in state.recommendations) return@LaunchedEffect
        state.categoryLoading = state.categoryLoading + category
        state.categoryErrors = state.categoryErrors - category
        try {
            val items = when (category) {
                RecommendationCategory.MOVIES -> api.doubanCategory("movie", "热门", "全部")
                RecommendationCategory.SHORT_DRAMAS -> api.shortDramaRecommendations()
                RecommendationCategory.ANIME -> api.bangumiToday()
                RecommendationCategory.SERIES -> api.doubanCategory("tv", "tv", "tv")
                RecommendationCategory.VARIETY -> api.doubanCategory("tv", "show", "show")
            }
            state.recommendations = state.recommendations + (category to items)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { state.categoryErrors = state.categoryErrors + (category to (e.message ?: "内容加载失败")) }
        finally { state.categoryLoading = state.categoryLoading - category }
    }
    LaunchedEffect(identity, revision) {
        if (state.identity != identity) state.reset(identity)
        state.libraryLoading = true
        try { state.records = watchItems(store.records()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
        try { state.favorites = watchItems(store.favorites()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
        state.libraryLoading = false
    }
    val visible = if (state.identity == identity) when (tab) {
        HomeTab.HOT -> state.recommendations[state.category].orEmpty()
        HomeTab.CONTINUE -> state.records
        HomeTab.FAVORITES -> state.favorites
    } else emptyList()
    val gridState = when (tab) {
        HomeTab.HOT -> state.categoryGrids.getValue(state.category)
        HomeTab.CONTINUE -> state.continueGrid
        HomeTab.FAVORITES -> state.favoritesGrid
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(site?.name ?: "MoonTVPlus", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSearch) { Text("搜索") }
            Button(onClick = onSettings) { Text("设置") }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeTab.entries.forEach { option -> HomeTabButton(option, tab == option) { onTabSelected(option) } }
        }
        Spacer(Modifier.height(12.dp))
        if (tab == HomeTab.HOT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecommendationCategory.entries.forEach { category ->
                    RecommendationTabButton(category, state.category == category) { state.category = category }
                }
            }
            Spacer(Modifier.height(12.dp))
            state.categoryErrors[state.category]?.let { Text(it, color = Color(0xFFFF8C8C)) }
        }
        if (visible.isEmpty()) {
            Text(
                if (state.identity != identity || if (tab == HomeTab.HOT) state.category in state.categoryLoading else state.libraryLoading) "正在加载…" else when (tab) {
                    HomeTab.HOT -> "暂无${state.category.label}"
                    HomeTab.CONTINUE -> "还没有观看记录"
                    HomeTab.FAVORITES -> "还没有收藏影片"
                },
                color = fieldHint,
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(visible) { _, item -> HomeVideoCard(api, item) { onSelect(item) } }
            }
        }
    }
}

@Composable
private fun HomeTabButton(tab: HomeTab, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused && !selected) onClick() },
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Color(0xFF6955AA) else Color(0xFF252B3D))
    ) { Text(tab.label, color = Color.White) }
}

@Composable
private fun RecommendationTabButton(category: RecommendationCategory, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { if (it.isFocused && !selected) onClick() },
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Color(0xFF6955AA) else Color(0xFF252B3D))
    ) { Text(category.label, color = Color.White) }
}

@Composable
private fun HomeVideoCard(api: MoonApi, item: VideoItem, metadata: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(item.poster, api.baseUrl) { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(BorderStroke(2.dp, Color.White), RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (focused) Color(0xFF323C58) else Color(0xFF1D2436))
    ) {
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(72.dp).height(108.dp).background(Color(0xFF303951), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    if (item.poster.isBlank() || imageFailed) {
                        Text("暂无海报", color = fieldHint, fontSize = 11.sp)
                    } else {
                        AsyncImage(
                            model = api.imageUrl(item.poster),
                            imageLoader = api.imageLoader,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop,
                            onError = { imageFailed = true }
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.fillMaxWidth().padding(end = if (item.rate.isNotBlank()) 38.dp else 0.dp)) {
                    Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(metadata ?: listOf(item.year, item.sourceName).filter { it.isNotBlank() }.joinToString(" · "), color = fieldHint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.rate.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(32.dp)
                        .background(Color(0xFFEC4899), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.rate, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(api: MoonApi, onSelect: (VideoItem) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchGroup>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    Column {
        Heading("搜索点播", "只显示服务端允许的普通点播源")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, {
                query = it
                searchGeneration++
                results = emptyList()
                message = ""
            }, label = { Text("片名") }, singleLine = true, colors = tvTextFieldColors(), modifier = Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    val submitted = query.trim()
                    val generation = ++searchGeneration
                    busy = true; message = "搜索中…"
                    try {
                        val found = api.search(submitted, api.sources())
                        if (generation == searchGeneration) {
                            val grouped = groupSearchResults(found)
                            results = grouped
                            message = if (grouped.isEmpty()) "没有找到与「$submitted」匹配的普通点播影片" else "找到 ${grouped.size} 部作品 · ${found.size} 条源结果"
                        }
                    } catch (e: Exception) { if (generation == searchGeneration) message = e.message ?: "搜索失败" }
                    busy = false
                }
            }, enabled = !busy && query.isNotBlank()) { Text("搜索") }
            Button(onClick = onBack) { Text("返回") }
        }
        if (message.isNotBlank()) Text(message, color = Color(0xFFADB8CD))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(results) { _, group ->
                val item = group.representative
                val metadata = listOfNotNull(
                    group.year.takeIf { it.isNotBlank() },
                    group.episodeCount.takeIf { it > 0 }?.let { "共${it}集" },
                    "${group.sourceNames.size}个源"
                ).joinToString(" · ")
                HomeVideoCard(api, item, metadata) { onSelect(item) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(api: MoonApi, store: WatchStore, site: SiteConfig?, onBack: () -> Unit, onServer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { Updater(context) }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloaded by remember { mutableStateOf<java.io.File?>(null) }
    var updateMessage by remember { mutableStateOf("") }
    Column {
        Heading("设置")
        Text("服务：${api.baseUrl}", color = Color.White)
        Text("服务版本：${site?.version ?: "未知"}", color = Color.White)
        Text("数据模式：${site?.storageType ?: "未知"}", color = Color.White)
        if (site?.storageType == "localstorage") Text("收藏和播放记录只保存在这台电视，无法与网页同步。", color = Color(0xFFFFBC7A))
        Button(onClick = onServer) { Text("更换服务") }
        Button(onClick = { store.clearCurrentLocal() }) { Text("清除当前用户本机记录") }
        Button(onClick = {
            scope.launch {
                updateMessage = "正在检查更新…"
                try { release = updater.latest(); updateMessage = if (release == null) "已经是最新版本" else "发现新版本 ${release!!.version}" }
                catch (e: Exception) { updateMessage = e.message ?: "检查更新失败" }
            }
        }) { Text("检查更新") }
        release?.let { available -> Button(onClick = {
            scope.launch {
                updateMessage = "正在下载更新…"
                try {
                    val file = updater.download(available)
                    downloaded = file
                    updater.install(file)
                    updateMessage = "请在 Android 系统界面确认安装；如需允许此来源安装，请返回后再点安装。"
                } catch (e: Exception) { updateMessage = e.message ?: "下载失败" }
            }
        }) { Text("下载并安装 ${available.version}") } }
        downloaded?.let { file -> Button(onClick = { updater.install(file) }) { Text("重新打开安装界面") } }
        if (updateMessage.isNotBlank()) Text(updateMessage, color = Color(0xFFFFBC7A))
        Button(onClick = onBack) { Text("返回") }
    }
}

private fun watchItems(data: org.json.JSONObject): List<VideoItem> = data.keys().asSequence().mapNotNull { key ->
    val plus = key.indexOf('+')
    if (plus < 1 || key.startsWith("live_")) return@mapNotNull null
    val value = data.optJSONObject(key) ?: return@mapNotNull null
    if (value.optString("origin") == "live") return@mapNotNull null
    value.optLong("save_time") to VideoItem(
        key.take(plus), key.drop(plus + 1), value.optString("title"),
        value.optString("cover"), value.optString("year"), value.optString("source_name")
    )
}.sortedByDescending { it.first }.map { it.second }.toList()
