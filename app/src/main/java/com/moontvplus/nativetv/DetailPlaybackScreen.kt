package com.moontvplus.nativetv

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class DetailTab { EPISODES, SOURCES }
private enum class FullPanel { NONE, EPISODES, SOURCES }
private val actions = listOf("暂停/播放", "上一集", "下一集", "选集", "换源")
private const val GRID_COLUMNS = 4
private enum class PlaybackGlyph { PLAY, PAUSE, PREVIOUS, NEXT }

@Composable
fun DetailPlaybackScreen(
    api: MoonApi,
    store: WatchStore,
    item: VideoItem,
    fullScreen: Boolean,
    onFullScreen: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(api, item.source, item.id) {
        val http = OkHttpDataSource.Factory(api.mediaClient())
            .setDefaultRequestProperties(mapOf("User-Agent" to "MoonTVPlus App NativeTV/0.1"))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
    }
    var active by remember(item.source, item.id) { mutableStateOf(item) }
    var sources by remember(item.source, item.id) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var episode by remember(item.source, item.id) { mutableIntStateOf(0) }
    var startTime by remember(item.source, item.id) { mutableStateOf(0L) }
    var playVersion by remember(item.source, item.id) { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var playWhenReady by remember { mutableStateOf(false) }
    var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var resolvingMedia by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }
    var detailTab by remember { mutableStateOf(DetailTab.EPISODES) }
    var menuVisible by remember { mutableStateOf(false) }
    var menuIndex by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(FullPanel.NONE) }
    var panelIndex by remember { mutableIntStateOf(0) }
    var previewFocused by remember { mutableStateOf(false) }
    var favoriteFocused by remember { mutableStateOf(false) }
    val previewFocus = remember { FocusRequester() }
    val favoriteFocus = remember { FocusRequester() }
    val episodesTabFocus = remember { FocusRequester() }
    val fullFocus = remember { FocusRequester() }
    val panelGridState = rememberLazyGridState()
    val detailGridState = rememberLazyGridState()

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(ready: Boolean, reason: Int) { playWhenReady = ready }
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
            override fun onPlayerError(exception: androidx.media3.common.PlaybackException) {
                error = exception.message ?: "播放失败"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    LaunchedEffect(item.source, item.id) {
        loading = true
        try {
            val allowed = api.sources()
            val matching = api.search(item.title, allowed).filter { sameWork(item, it) }
            val initial = if (item.source.isNotBlank() && item.id.isNotBlank()) api.detail(item.source, item.id)
                else matching.firstOrNull()?.let { api.detail(it.source, it.id) }
                    ?: error("没有找到可播放的普通点播源")
            val record = store.records().optJSONObject("${initial.source}+${initial.id}")
            val resumeEpisode = ((record?.optInt("index", 1) ?: 1) - 1).coerceAtLeast(0)
            sources = (matching + initial).distinctBy { it.source to it.id }.filter { sameWork(initial, it) }
            val selected = SourceSelector(api).fastest(sources, resumeEpisode) ?: initial
            active = if (selected.episodes.isNotEmpty()) selected else api.detail(selected.source, selected.id)
            episode = resumeEpisode.coerceAtMost((active.episodes.size - 1).coerceAtLeast(0))
            startTime = if (active.source == initial.source && active.id == initial.id) {
                (record?.optLong("play_time", 0) ?: 0) * 1000L
            } else 0L
            favorite = store.favorites().has("${active.source}+${active.id}")
            playVersion++
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "详情加载失败" }
        finally { loading = false }
    }

    LaunchedEffect(active.source, active.id, episode, playVersion) {
        if (playVersion == 0 || active.episodes.isEmpty()) return@LaunchedEffect
        error = ""
        resolvingMedia = true
        try {
            val url = api.resolveEpisode(active.episodes[episode], active.source, active.proxyMode)
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            if (startTime > 0) player.seekTo(startTime)
            player.playWhenReady = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法播放" }
        finally { resolvingMedia = false }
    }

    LaunchedEffect(active.source, active.id, episode) {
        while (true) {
            delay(10000)
            if (player.currentPosition > 1000) {
                try {
                    store.saveRecord("${active.source}+${active.id}", JSONObject()
                        .put("title", active.title).put("source_name", active.sourceName)
                        .put("year", active.year).put("cover", active.poster)
                        .put("index", episode + 1).put("total_episodes", active.episodes.size)
                        .put("play_time", player.currentPosition / 1000)
                        .put("total_time", player.duration.coerceAtLeast(0) / 1000)
                        .put("save_time", System.currentTimeMillis()))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
        }
    }

    fun chooseEpisode(index: Int) {
        if (index !in active.episodes.indices) return
        episode = index
        startTime = 0L
        playVersion++
        panel = FullPanel.NONE
        menuVisible = false
    }

    fun chooseSource(index: Int) {
        val candidate = sources.getOrNull(index) ?: return
        scope.launch {
            loading = true
            try {
                val next = api.detail(candidate.source, candidate.id)
                require(sameWork(active, next) && next.episodes.isNotEmpty()) { "此播放源没有可用选集" }
                active = next
                episode = episode.coerceAtMost(next.episodes.lastIndex)
                startTime = 0L
                playVersion++
                favorite = store.favorites().has("${next.source}+${next.id}")
                error = ""
                panel = FullPanel.NONE
                menuVisible = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "换源失败" }
            finally { loading = false }
        }
    }

    fun runAction(index: Int) {
        when (index) {
            0 -> if (player.playWhenReady) player.pause() else player.play()
            1 -> if (episode > 0) chooseEpisode(episode - 1)
            2 -> if (episode + 1 < active.episodes.size) chooseEpisode(episode + 1)
            3 -> { panel = FullPanel.EPISODES; panelIndex = episode }
            4 -> { panel = FullPanel.SOURCES; panelIndex = sources.indexOfFirst { it.source == active.source && it.id == active.id }.coerceAtLeast(0) }
        }
    }

    fun actionEnabled(index: Int): Boolean = when (index) {
        1 -> episode > 0
        2 -> episode + 1 < active.episodes.size
        else -> true
    }

    LaunchedEffect(episode, active.episodes.size) {
        if (!actionEnabled(menuIndex)) menuIndex = 0
    }

    LaunchedEffect(fullScreen, active.id) {
        if (fullScreen) fullFocus.requestFocus()
        else {
            menuVisible = false
            panel = FullPanel.NONE
            detailGridState.scrollToItem(0)
            previewFocus.requestFocus()
        }
    }
    BackHandler(fullScreen) {
        when {
            panel != FullPanel.NONE -> panel = FullPanel.NONE
            menuVisible -> menuVisible = false
            else -> onBack()
        }
    }
    LaunchedEffect(panel, panelIndex) {
        if (panel != FullPanel.NONE && panelIndex >= 0) {
            val layout = panelGridState.layoutInfo
            val selected = layout.visibleItemsInfo.firstOrNull { it.index == panelIndex }
            if (selected == null || selected.offset.y < layout.viewportStartOffset ||
                selected.offset.y + selected.size.height > layout.viewportEndOffset) {
                panelGridState.scrollToItem(panelIndex)
            }
        }
    }

    if (fullScreen) {
        Box(Modifier.fillMaxSize().focusRequester(fullFocus)
            .onPreviewKeyEvent { key ->
                if (key.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (key.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (panel != FullPanel.NONE) {
                            if (panel == FullPanel.EPISODES) chooseEpisode(panelIndex) else chooseSource(panelIndex)
                        } else if (menuVisible) runAction(menuIndex)
                        else if (player.playWhenReady) player.pause() else player.play()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (panel != FullPanel.NONE) panelIndex = (panelIndex + GRID_COLUMNS).coerceAtMost(
                            if (panel == FullPanel.EPISODES) active.episodes.lastIndex else sources.lastIndex
                        ) else if (!menuVisible) menuVisible = true
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (panel != FullPanel.NONE) panelIndex = (panelIndex - GRID_COLUMNS).coerceAtLeast(0)
                        else if (menuVisible) menuVisible = false
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val step = if (key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        if (panel != FullPanel.NONE) {
                            val count = if (panel == FullPanel.EPISODES) active.episodes.size else sources.size
                            panelIndex = (panelIndex + step)
                                .coerceIn(0, (count - 1).coerceAtLeast(0))
                        } else if (menuVisible) {
                            var candidate = menuIndex + step
                            while (candidate in actions.indices && !actionEnabled(candidate)) candidate += step
                            if (candidate in actions.indices) menuIndex = candidate
                        } else {
                            player.seekTo((player.currentPosition + step * 10000).coerceAtLeast(0))
                        }
                        true
                    }
                    else -> false
                }
            }.focusable()) {
            PlaybackView(player, Modifier.fillMaxSize())
            val paused = !playWhenReady && playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING) && error.isBlank()
            val buffering = error.isBlank() && (resolvingMedia || loading || playWhenReady && playbackState == Player.STATE_BUFFERING)
            if (paused) {
                Box(Modifier.align(Alignment.Center).size(88.dp)
                    .background(Color(0xB8000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    PlaybackIcon(PlaybackGlyph.PAUSE, Modifier.size(42.dp))
                }
            }
            if (buffering && !paused) {
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color(0xB8000000), RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    Text("正在加载…", color = Color.White, fontSize = 16.sp)
                }
            }
            if (menuVisible || panel != FullPanel.NONE) {
                Text("${active.title}  ·  第 ${episode + 1} 集", color = Color.White, fontSize = 22.sp,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0x88000000)).padding(16.dp))
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9999),
                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(16.dp))
            if (menuVisible || panel != FullPanel.NONE) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xDD10131D)).padding(16.dp)) {
                    if (panel == FullPanel.NONE) {
                        Text("←/→ 选择操作 · 确定执行 · 返回隐藏菜单", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.forEachIndexed { index, label ->
                                if (index < 3) {
                                    PlaybackActionChip(index, index == 0 && playWhenReady, menuIndex == index,
                                        actionEnabled(index), label) { menuIndex = index; runAction(index) }
                                } else {
                                    ActionChip(label, menuIndex == index) { menuIndex = index; runAction(index) }
                                }
                            }
                        }
                    } else {
                        val labels = if (panel == FullPanel.EPISODES) active.episodes.indices.map { i ->
                            "${i + 1}. ${active.episodeTitles.getOrNull(i) ?: "第 ${i + 1} 集"}"
                        } else sources.map { "${it.sourceName.ifBlank { it.source }} · ${it.source}" }
                        Text(if (panel == FullPanel.EPISODES) "选集 · 方向键选择 · 确定播放 · 返回上级菜单" else "换源 · 方向键选择 · 确定播放 · 返回上级菜单", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS), state = panelGridState,
                            modifier = Modifier.fillMaxWidth().height(210.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(labels) { index, label ->
                                ActionChip(label, panelIndex == index, modifier = Modifier.fillMaxWidth()) {
                                    panelIndex = index
                                    if (panel == FullPanel.EPISODES) chooseEpisode(index) else chooseSource(index)
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(columns = GridCells.Fixed(GRID_COLUMNS), state = detailGridState,
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(active.title, color = Color.White, fontSize = 28.sp)
                    Text(listOf(active.year, active.sourceName).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFFADB8CD))
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().height(270.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(7f).fillMaxSize().focusRequester(previewFocus)
                            .focusProperties { right = favoriteFocus; down = episodesTabFocus }
                            .onFocusChanged { previewFocused = it.isFocused }
                            .onPreviewKeyEvent { key ->
                                if (key.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    (key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || key.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                                    onFullScreen(); true
                                } else false
                            }.focusable()
                            .border(BorderStroke(if (previewFocused) 3.dp else 1.dp,
                                if (previewFocused) Color.White else Color(0xFF444B61)), RoundedCornerShape(10.dp))) {
                            PlaybackView(player, Modifier.fillMaxSize())
                            Text("确定 · 全屏播放", color = Color.White, modifier = Modifier.align(Alignment.BottomStart)
                                .background(Color(0x99000000)).padding(10.dp))
                            if (loading) Text("正在测速并加载预览…", color = Color.White,
                                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(12.dp))
                            if (error.isNotBlank()) Text(error, color = Color(0xFFFF9999),
                                modifier = Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(12.dp))
                        }
                        Column(Modifier.weight(3f).fillMaxSize()
                            .background(Color(0xFF1B2030), RoundedCornerShape(10.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("影片信息", color = Color.White, fontSize = 20.sp)
                                val facts = listOfNotNull(
                                    active.year.takeIf { it.isNotBlank() && it != "unknown" },
                                    active.category.takeIf { it.isNotBlank() },
                                    active.episodes.size.takeIf { it > 0 }?.let { "$it 集" }
                                )
                                Text(facts.joinToString(" · "), color = Color(0xFFADB8CD), fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (active.remarks.isNotBlank()) {
                                    Text("更新：${active.remarks}", color = Color(0xFFADB8CD), fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (active.description.isNotBlank()) {
                                    Text(active.description, color = Color(0xFFD5DBE7), fontSize = 14.sp,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box(modifier = Modifier.focusRequester(favoriteFocus)
                                .focusProperties { left = previewFocus; down = episodesTabFocus }
                                .onFocusChanged { favoriteFocused = it.isFocused }
                                .width(132.dp).height(48.dp)
                                .background(Color(0xFF6955AA), RoundedCornerShape(50))
                                .border(BorderStroke(2.dp, if (favoriteFocused) Color.White else Color.Transparent), RoundedCornerShape(50))
                                .clickable {
                                    scope.launch {
                                        try {
                                            val key = "${active.source}+${active.id}"
                                            if (favorite) { store.deleteFavorite(key); favorite = false }
                                            else {
                                                store.saveFavorite(key, JSONObject().put("title", active.title)
                                                    .put("source_name", active.sourceName).put("year", active.year)
                                                    .put("cover", active.poster).put("total_episodes", active.episodes.size)
                                                    .put("save_time", System.currentTimeMillis()))
                                                favorite = true
                                            }
                                        } catch (e: Exception) { error = e.message ?: "收藏失败" }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Text(if (favorite) "取消收藏" else "收藏", color = Color.White, fontSize = 16.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { detailTab = DetailTab.EPISODES }, modifier = Modifier.focusRequester(episodesTabFocus),
                            colors = ButtonDefaults.buttonColors(containerColor = if (detailTab == DetailTab.EPISODES) Color(0xFF6955AA) else Color(0xFF252B3D))) { Text("选集") }
                        Button(onClick = { detailTab = DetailTab.SOURCES },
                            colors = ButtonDefaults.buttonColors(containerColor = if (detailTab == DetailTab.SOURCES) Color(0xFF6955AA) else Color(0xFF252B3D))) { Text("选源") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (detailTab == DetailTab.EPISODES) {
                itemsIndexed(active.episodes) { index, _ ->
                    Button(onClick = { chooseEpisode(index) }, modifier = Modifier.fillMaxWidth().height(68.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (episode == index) Color(0xFF6955AA) else Color(0xFF252B3D))) {
                        Text("${index + 1}. ${active.episodeTitles.getOrNull(index) ?: "第 ${index + 1} 集"}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                itemsIndexed(sources) { index, source ->
                    Button(onClick = { chooseSource(index) }, modifier = Modifier.fillMaxWidth().height(68.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (source.source == active.source && source.id == active.id) Color(0xFF6955AA) else Color(0xFF252B3D))) {
                        Text("${source.sourceName.ifBlank { source.source }} · ${source.source}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackView(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply {
            useController = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        } },
        update = { it.player = player }, modifier = modifier
    )
}

@Composable
private fun ActionChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.background(if (selected) Color(0xFF6955AA) else Color(0xFF30374A), RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaybackActionChip(
    index: Int,
    isPlaying: Boolean,
    selected: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val glyph = when (index) {
        0 -> if (isPlaying) PlaybackGlyph.PAUSE else PlaybackGlyph.PLAY
        1 -> PlaybackGlyph.PREVIOUS
        else -> PlaybackGlyph.NEXT
    }
    Box(Modifier.size(58.dp)
        .background(if (!enabled) Color(0xFF222733) else if (selected) Color(0xFF6955AA) else Color(0xFF30374A),
            RoundedCornerShape(8.dp))
        .semantics {
            contentDescription = if (index == 0) (if (isPlaying) "暂停" else "播放") else label
            if (!enabled) disabled()
        }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        PlaybackIcon(glyph, Modifier.size(30.dp), if (enabled) Color.White else Color(0xFF777E8D))
    }
}

@Composable
private fun PlaybackIcon(glyph: PlaybackGlyph, modifier: Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun triangle(left: Float, right: Float, pointsRight: Boolean) {
            val path = Path().apply {
                if (pointsRight) {
                    moveTo(left, h * .16f)
                    lineTo(right, h * .5f)
                    lineTo(left, h * .84f)
                } else {
                    moveTo(right, h * .16f)
                    lineTo(left, h * .5f)
                    lineTo(right, h * .84f)
                }
                close()
            }
            drawPath(path, color)
        }
        when (glyph) {
            PlaybackGlyph.PLAY -> triangle(w * .22f, w * .82f, true)
            PlaybackGlyph.PAUSE -> {
                drawRoundRect(color, Offset(w * .22f, h * .16f), Size(w * .18f, h * .68f))
                drawRoundRect(color, Offset(w * .60f, h * .16f), Size(w * .18f, h * .68f))
            }
            PlaybackGlyph.PREVIOUS -> {
                drawRoundRect(color, Offset(w * .14f, h * .16f), Size(w * .13f, h * .68f))
                triangle(w * .28f, w * .84f, false)
            }
            PlaybackGlyph.NEXT -> {
                triangle(w * .16f, w * .72f, true)
                drawRoundRect(color, Offset(w * .73f, h * .16f), Size(w * .13f, h * .68f))
            }
        }
    }
}
